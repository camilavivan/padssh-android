package com.padssh.app.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.padssh.app.MainActivity
import com.padssh.app.PadSshApplication
import com.padssh.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that keeps the process alive while an SSH session is connected.
 * Holds no duplicate SSH client — uses [PadSshApplication.sshManager].
 *
 * Acquires PARTIAL_WAKE_LOCK + WifiLock so OEM Wi‑Fi radio sleep does not kill TCP.
 * While Connected, runs a non-daemon keepalive pulse every 5s (no auto-reconnect).
 */
class SshSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var observing = false
    @Volatile
    private var keepAliveThread: Thread? = null
    private val keepAliveStop = AtomicBoolean(true)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            val app = application as PadSshApplication
            scope.launch(Dispatchers.IO) {
                app.sshManager.disconnect()
            }
            stopSession()
            return START_NOT_STICKY
        }

        val label = intent?.getStringExtra(EXTRA_LABEL)
            ?: (application as PadSshApplication).let { app ->
                (app.sshManager.connectionState.value as? ConnectionState.Connected)?.label
            }
            ?: "SSH"

        createChannel()
        val notification = buildNotification(label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()
        acquireWifiLock()
        observeConnection()
        startKeepAliveLoop()

        val state = (application as PadSshApplication).sshManager.connectionState.value
        return if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    /**
     * User swiped the task away / closed the app from recents = explicit close.
     * Disconnect SSH and stop the service.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val app = application as PadSshApplication
        // Block until disconnect finishes — process may be killed soon after.
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                app.sshManager.disconnect()
            }
        } catch (e: Exception) {
            android.util.Log.w("PadSSH", "onTaskRemoved disconnect: ${e.message}")
        }
        stopSession()
        super.onTaskRemoved(rootIntent)
    }

    private fun observeConnection() {
        if (observing) return
        observing = true
        val app = application as PadSshApplication
        scope.launch {
            app.sshManager.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIFICATION_ID, buildNotification(state.label))
                        startKeepAliveLoop()
                        // Do NOT stop FGS while Connected.
                    }
                    is ConnectionState.Disconnected, is ConnectionState.Failed -> {
                        stopKeepAliveLoop()
                        stopSession()
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Non-daemon keepalive thread: every 5s while Connected, call [SshManager.pulseKeepAlive].
     * No reconnect — only detects dead TCP and marks Failed.
     */
    private fun startKeepAliveLoop() {
        val existing = keepAliveThread
        if (existing != null && existing.isAlive && !keepAliveStop.get()) return
        stopKeepAliveLoop()
        keepAliveStop.set(false)
        val app = application as PadSshApplication
        val t = Thread({
            android.util.Log.d("PadSSH", "keepalive thread started")
            try {
                while (!keepAliveStop.get() && !Thread.currentThread().isInterrupted) {
                    val state = app.sshManager.connectionState.value
                    if (state !is ConnectionState.Connected) break
                    try {
                        app.sshManager.pulseKeepAlive()
                    } catch (e: Exception) {
                        android.util.Log.w("PadSSH", "keepalive pulse error: ${e.message}")
                    }
                    try {
                        Thread.sleep(SshManager.KEEP_ALIVE_INTERVAL_SEC * 1_000L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } finally {
                android.util.Log.d("PadSSH", "keepalive thread ended")
            }
        }, "padssh-keepalive")
        t.isDaemon = false
        keepAliveThread = t
        t.start()
    }

    private fun stopKeepAliveLoop() {
        keepAliveStop.set(true)
        keepAliveThread?.interrupt()
        keepAliveThread = null
    }

    private fun stopSession() {
        stopKeepAliveLoop()
        releaseWifiLock()
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PadSSH::Session").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "PadSSH::Wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.session_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.session_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(label: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TERMINAL, true)
        }
        val openPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disconnectIntent = Intent(this, SshSessionService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPi = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.session_notification_title, label))
            .setContentText(getString(R.string.session_notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.disconnect),
                disconnectPi,
            )
            .build()
    }

    override fun onDestroy() {
        stopKeepAliveLoop()
        releaseWifiLock()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "padssh_session"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_LABEL = "label"
        const val EXTRA_OPEN_TERMINAL = "open_terminal"
        const val ACTION_DISCONNECT = "com.padssh.app.ACTION_DISCONNECT"

        fun start(context: Context, label: String) {
            val intent = Intent(context, SshSessionService::class.java).apply {
                putExtra(EXTRA_LABEL, label)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SshSessionService::class.java))
        }
    }
}
