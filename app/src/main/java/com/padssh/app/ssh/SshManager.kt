package com.padssh.app.ssh

import com.padssh.app.data.HostEntity
import com.padssh.app.data.HostRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val hostId: Long, val label: String) : ConnectionState()
    data class Failed(val message: String) : ConnectionState()
}

class SshManager(private val repository: HostRepository) {

    init {
        // Ensure full BC is installed (also done early in PadSshApplication).
        SecurityProviders.install()
    }

    private var client: SSHClient? = null
    /** Host used for the active shell session; also used to open a dedicated SFTP connection. */
    private var currentHost: HostEntity? = null
    private var shell: Session.Shell? = null
    private var session: Session? = null

    /** Dedicated SSHClient for SFTP only — never share with the interactive shell (sshj #532/#461). */
    private var sftpClient: SSHClient? = null
    private var sftp: SFTPClient? = null
    private val sftpLock = Any()

    private val reading = AtomicBoolean(false)
    /** Guards against concurrent silent shell reattach attempts. */
    private val shellReattaching = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _terminalOutput = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val terminalOutput: SharedFlow<String> = _terminalOutput.asSharedFlow()

    /** Session-scoped terminal buffer that survives Activity recreation. */
    private val _terminalBuffer = MutableStateFlow("")
    val terminalBuffer: StateFlow<String> = _terminalBuffer.asStateFlow()

    private val _hostKeyPrompt = MutableStateFlow<HostKeyDecision?>(null)
    val hostKeyPrompt: StateFlow<HostKeyDecision?> = _hostKeyPrompt.asStateFlow()

    private var tofuVerifier: TofuHostKeyVerifier? = null

    fun resolveHostKey(trust: Boolean) {
        tofuVerifier?.resolvePrompt(trust)
        _hostKeyPrompt.value = null
    }

    suspend fun connect(host: HostEntity): Result<Unit> = withContext(Dispatchers.IO) {
        disconnectInternal(clearHost = true)
        clearTerminalBuffer()
        _connectionState.value = ConnectionState.Connecting
        try {
            val ssh = openAuthenticatedClient(host)
            client = ssh
            currentHost = host
            _connectionState.value = ConnectionState.Connected(host.id, host.name)
            Result.success(Unit)
        } catch (e: Exception) {
            disconnectInternal(clearHost = true)
            val msg = e.message ?: e.javaClass.simpleName
            _connectionState.value = ConnectionState.Failed(msg)
            Result.failure(e)
        }
    }

    private fun openAuthenticatedClient(host: HostEntity): SSHClient {
        val ssh = SSHClient(AndroidSshConfig())
        val verifier = tofuVerifier ?: TofuHostKeyVerifier(repository) { decision ->
            _hostKeyPrompt.value = decision
        }
        tofuVerifier = verifier
        ssh.addHostKeyVerifier(verifier)
        ssh.connectTimeout = 15_000
        // 0 = infinite socket read timeout; shell reader thread blocks on read.
        // Keep-alive handles idle detection instead of a short read timeout.
        ssh.timeout = 0
        ssh.connect(host.host, host.port)
        enableTcpKeepalive(ssh)
        authClient(ssh, host)
        // Aggressive SSH keep-alive for OEM NAT / aggressive Wi-Fi sleep.
        ssh.connection.keepAlive.keepAliveInterval = KEEP_ALIVE_INTERVAL_SEC
        return ssh
    }

    /** Enable OS-level TCP keepalive + Nagle disable on the underlying socket. */
    private fun enableTcpKeepalive(ssh: SSHClient) {
        try {
            val socket = ssh.socket ?: return
            socket.keepAlive = true
            socket.tcpNoDelay = true
        } catch (e: Exception) {
            android.util.Log.w("PadSSH", "enableTcpKeepalive failed: ${e.message}")
        }
    }

    private fun authClient(ssh: SSHClient, host: HostEntity) {
        if (host.useKeyAuth && !host.privateKey.isNullOrBlank()) {
            val tmp = File.createTempFile("padssh_key", ".pem")
            try {
                tmp.writeText(host.privateKey)
                val keys: KeyProvider = if (!host.keyPassphrase.isNullOrBlank()) {
                    ssh.loadKeys(tmp.absolutePath, host.keyPassphrase)
                } else {
                    ssh.loadKeys(tmp.absolutePath)
                }
                ssh.authPublickey(host.username, keys)
            } finally {
                tmp.delete()
            }
        } else {
            ssh.authPassword(host.username, host.password ?: "")
        }
    }

    suspend fun startShell(cols: Int = 80, rows: Int = 24) = withContext(Dispatchers.IO) {
        startShellInternal(cols, rows)
    }

    private fun startShellInternal(cols: Int = 80, rows: Int = 24) {
        val ssh = client ?: error("未连接")
        stopShellInternal()
        val sess = ssh.startSession()
        sess.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap<net.schmizz.sshj.connection.channel.direct.PTYMode, Int>())
        val sh = sess.startShell()
        session = sess
        shell = sh
        reading.set(true)
        Thread({
            val buf = ByteArray(4096)
            var unexpected = false
            try {
                val input: InputStream = sh.inputStream
                while (reading.get()) {
                    val n = input.read(buf)
                    if (n < 0) {
                        unexpected = reading.get()
                        break
                    }
                    if (n > 0) {
                        appendTerminal(String(buf, 0, n, Charsets.UTF_8))
                    }
                }
            } catch (_: Exception) {
                unexpected = reading.get()
            }
            if (unexpected) {
                reading.set(false)
                onShellReaderEndedUnexpectedly()
            }
        }, "padssh-shell-reader").also {
            // Non-daemon: keep process alive while the shell is reading.
            it.isDaemon = false
            it.start()
        }
    }

    /**
     * Shell channel died unexpectedly. Prefer silent shell reattach when TCP+auth
     * are still alive (no user-visible reconnect UX). Otherwise mark Failed quietly.
     * Never reconnects TCP / re-auths.
     */
    private fun onShellReaderEndedUnexpectedly() {
        val ssh = client
        if (ssh != null && ssh.isConnected && ssh.isAuthenticated) {
            if (!shellReattaching.compareAndSet(false, true)) return
            try {
                android.util.Log.i("PadSSH", "shell channel ended; silently reopening shell (TCP still up)")
                startShellInternal()
            } catch (e: Exception) {
                android.util.Log.w("PadSSH", "silent shell reattach failed: ${e.message}", e)
                markTransportDead()
            } finally {
                shellReattaching.set(false)
            }
        } else {
            markTransportDead()
        }
    }

    private fun markTransportDead(detail: String? = null) {
        disconnectInternal(clearHost = true)
        val msg = if (detail.isNullOrBlank()) "连接已断开" else "连接已断开：$detail"
        _connectionState.value = ConnectionState.Failed(msg)
    }

    /**
     * Periodic keepalive pulse from [SshSessionService]. Sends an SSH keepalive
     * global request when possible and verifies the TCP client is still connected.
     * No reconnect — if dead, sets Failed("连接已断开").
     */
    fun pulseKeepAlive() {
        val state = _connectionState.value
        if (state !is ConnectionState.Connected) return
        val ssh = client
        if (ssh == null || !ssh.isConnected) {
            markTransportDead()
            return
        }
        try {
            // Prefer sshj built-in keepalive send path via global request (same as KeepAliveRunner).
            ssh.connection.sendGlobalRequest("keepalive@openssh.com", true, ByteArray(0))
        } catch (e: Exception) {
            android.util.Log.w("PadSSH", "pulseKeepAlive send failed: ${e.message}")
        }
        if (!ssh.isConnected) {
            markTransportDead()
        }
    }

    private fun appendTerminal(chunk: String) {
        _terminalOutput.tryEmit(chunk)
        val next = _terminalBuffer.value + chunk
        _terminalBuffer.value = if (next.length > 200_000) next.takeLast(150_000) else next
    }

    fun clearTerminalBuffer() {
        _terminalBuffer.value = ""
    }

    private val writeLock = Any()
    private val writeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        // Non-daemon writer so session work is not killed by GC of daemon threads.
        Thread(r, "padssh-shell-writer").also { it.isDaemon = false }
    }

    @Volatile
    var lastWriteError: String? = null
        private set

    fun writeToShell(data: String) {
        writeBytesToShell(data.toByteArray(Charsets.UTF_8))
    }

    fun writeBytesToShell(bytes: ByteArray) {
        // Never block the main/UI thread on a slow PTY write.
        writeExecutor.execute {
            synchronized(writeLock) {
                try {
                    var out: OutputStream? = shell?.outputStream
                    if (out == null) {
                        // One retry in case shell was just attached.
                        out = shell?.outputStream
                    }
                    if (out == null) {
                        lastWriteError = "shell outputStream is null"
                        android.util.Log.w("PadSSH", lastWriteError!!)
                        return@synchronized
                    }
                    out.write(bytes)
                    out.flush()
                    lastWriteError = null
                } catch (e: Exception) {
                    lastWriteError = e.message ?: e.javaClass.simpleName
                    android.util.Log.w("PadSSH", "writeBytesToShell failed: $lastWriteError", e)
                }
            }
        }
    }

    suspend fun changeWindowSize(cols: Int, rows: Int) = withContext(Dispatchers.IO) {
        try {
            shell?.changeWindowDimensions(cols, rows, 0, 0)
        } catch (_: Exception) {
        }
    }

    /**
     * Ensures a dedicated secondary SSH connection for SFTP (separate from the shell client).
     * Reuses [sftpClient] while it is alive; recreates if dead.
     */
    suspend fun openSftp(): SFTPClient = withContext(Dispatchers.IO) {
        synchronized(sftpLock) {
            ensureSftpClientLocked()
        }
    }

    fun getSftp(): SFTPClient? = synchronized(sftpLock) { sftp }

    private fun isSftpAlive(): Boolean {
        val ssh = sftpClient ?: return false
        return try {
            ssh.isConnected && ssh.isAuthenticated
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureSftpClientLocked(): SFTPClient {
        if (sftp != null && isSftpAlive()) {
            return sftp!!
        }
        closeSftpInternal()

        val host = currentHost ?: error("未连接：无法打开 SFTP（无主机信息）")
        if (client == null || client?.isConnected != true) {
            error("未连接：请先建立 SSH 会话后再打开文件传输")
        }

        try {
            val ssh = SSHClient(AndroidSshConfig())
            // Prefer keys already stored after the shell connect; reuse same TOFU verifier
            // so known keys auto-trust and unknown/changed keys can still prompt.
            val verifier = tofuVerifier ?: TofuHostKeyVerifier(repository) { decision ->
                _hostKeyPrompt.value = decision
            }
            ssh.addHostKeyVerifier(verifier)
            ssh.connectTimeout = 15_000
            ssh.timeout = 30_000
            ssh.connect(host.host, host.port)
            enableTcpKeepalive(ssh)
            authClient(ssh, host)
            ssh.connection.keepAlive.keepAliveInterval = KEEP_ALIVE_INTERVAL_SEC

            val sftpChannel = ssh.newSFTPClient()
            sftpClient = ssh
            sftp = sftpChannel
            return sftpChannel
        } catch (e: Exception) {
            closeSftpInternal()
            throw IllegalStateException(
                "SFTP 连接失败：无法建立独立的文件传输连接。${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
    }

    suspend fun listRemote(path: String): List<RemoteResourceInfo> = withContext(Dispatchers.IO) {
        synchronized(sftpLock) {
            val client = ensureSftpClientLocked()
            client.ls(path).sortedWith(
                compareBy<RemoteResourceInfo> { !it.isDirectory }.thenBy { it.name.lowercase() },
            )
        }
    }

    suspend fun downloadFile(remotePath: String, localFile: File) = withContext(Dispatchers.IO) {
        synchronized(sftpLock) {
            val client = ensureSftpClientLocked()
            client.get(remotePath, localFile.absolutePath)
        }
    }

    suspend fun uploadFile(localFile: File, remotePath: String) = withContext(Dispatchers.IO) {
        synchronized(sftpLock) {
            val client = ensureSftpClientLocked()
            client.put(localFile.absolutePath, remotePath)
        }
    }

    /** Canonicalize a remote path on the dedicated SFTP connection. */
    suspend fun canonicalizeRemote(path: String): String = withContext(Dispatchers.IO) {
        synchronized(sftpLock) {
            val client = ensureSftpClientLocked()
            client.canonicalize(path)
        }
    }

    /** User-initiated disconnect: tears everything down. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        disconnectInternal(clearHost = true)
        clearTerminalBuffer()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun closeSftpInternal() {
        sftp?.closeQuietly()
        sftp = null
        try {
            sftpClient?.disconnect()
        } catch (_: Exception) {
        }
        sftpClient = null
    }

    private fun disconnectInternal(clearHost: Boolean) {
        reading.set(false)
        stopShellInternal()
        closeSftpInternal()
        try {
            client?.disconnect()
        } catch (_: Exception) {
        }
        client = null
        if (clearHost) {
            currentHost = null
            tofuVerifier = null
            _hostKeyPrompt.value = null
        }
    }

    private fun stopShellInternal() {
        try {
            shell?.close()
        } catch (_: Exception) {
        }
        try {
            session?.close()
        } catch (_: Exception) {
        }
        shell = null
        session = null
    }

    private fun SFTPClient.closeQuietly() {
        try {
            close()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Seconds between SSH-level keepalives (sshj KeepAlive + service pulse). */
        const val KEEP_ALIVE_INTERVAL_SEC = 5
    }
}
