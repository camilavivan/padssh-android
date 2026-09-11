package com.padssh.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.padssh.app.data.HostEntity
import com.padssh.app.ssh.ConnectionState
import com.padssh.app.ssh.HostKeyDecision
import com.padssh.app.ssh.SshSessionService
import com.padssh.app.ui.hosts.HostDetailPlaceholder
import com.padssh.app.ui.hosts.HostEditScreen
import com.padssh.app.ui.hosts.HostListPane
import com.padssh.app.ui.hosts.TabletHostLayout
import com.padssh.app.ui.sftp.SftpScreen
import com.padssh.app.ui.terminal.TerminalScreen
import com.padssh.app.ui.theme.PadSshTheme
import com.padssh.app.viewmodel.HostViewModel
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {

    private val openTerminalEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Set by [TerminalScreen] while visible. Consumes Bluetooth / hardware keys
     * even when Compose focus is not on the input field.
     */
    @Volatile
    var terminalKeyHandler: ((KeyEvent) -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = terminalKeyHandler
        if (handler != null && handler(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        enableEdgeToEdge()
        val app = application as PadSshApplication
        if (intent?.getBooleanExtra(SshSessionService.EXTRA_OPEN_TERMINAL, false) == true) {
            openTerminalEvents.tryEmit(Unit)
        }
        setContent {
            PadSshTheme {
                val widthClass = calculateWindowSizeClass(this).widthSizeClass
                val vm: HostViewModel = viewModel(
                    factory = HostViewModel.Factory(app, app.repository, app.sshManager),
                )
                PadSshNav(
                    vm = vm,
                    isExpanded = widthClass != WindowWidthSizeClass.Compact,
                    openTerminalEvents = openTerminalEvents,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(SshSessionService.EXTRA_OPEN_TERMINAL, false)) {
            openTerminalEvents.tryEmit(Unit)
        }
    }
}

@Composable
private fun PadSshNav(
    vm: HostViewModel,
    isExpanded: Boolean,
    openTerminalEvents: MutableSharedFlow<Unit>,
) {
    val nav = rememberNavController()
    val hosts by vm.hosts.collectAsState()
    val conn by vm.connectionState.collectAsState()
    val prompt by vm.hostKeyPrompt.collectAsState()
    val context = LocalContext.current
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var pendingConnect by remember { mutableStateOf<HostEntity?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingConnect?.let { host ->
            pendingConnect = null
            doConnect(vm, host, context) { nav.navigate("terminal/${host.id}") }
        }
    }

    fun requestNotifThenConnect(host: HostEntity) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingConnect = host
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        doConnect(vm, host, context) { nav.navigate("terminal/${host.id}") }
    }

    HostKeyDialog(prompt) { trust -> vm.resolveHostKey(trust) }

    LaunchedEffect(openTerminalEvents) {
        openTerminalEvents.collect {
            navigateToConnectedTerminal(nav, vm.connectionState.value)
        }
    }

    val connected = conn as? ConnectionState.Connected

    // Single root so the connecting spinner is a true overlay (not a sibling that
    // can linger / steal hits). Overlay is only present while Connecting.
    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = "home") {
            composable("home") {
                val selected = hosts.find { it.id == selectedId }
                val returnToTerminal: (() -> Unit)? = connected?.let { c ->
                    { nav.navigate("terminal/${c.hostId}") }
                }
                if (isExpanded) {
                    TabletHostLayout(
                        list = { mod ->
                            HostListPane(
                                hosts = hosts,
                                selectedId = selectedId,
                                onSelect = { selectedId = it.id },
                                onAdd = { nav.navigate("edit/0") },
                                onEdit = { nav.navigate("edit/${it.id}") },
                                onDelete = {
                                    vm.deleteHost(it)
                                    if (selectedId == it.id) selectedId = null
                                },
                                onConnect = { host -> requestNotifThenConnect(host) },
                                connectedLabel = connected?.label,
                                onReturnToTerminal = returnToTerminal,
                                modifier = mod,
                            )
                        },
                        detail = { mod ->
                            Box(mod) {
                                if (selected == null) {
                                    HostDetailPlaceholder()
                                } else {
                                    HostQuickDetail(
                                        host = selected,
                                        onEdit = { nav.navigate("edit/${selected.id}") },
                                        onConnect = { requestNotifThenConnect(selected) },
                                        connectedLabel = connected?.takeIf { it.hostId == selected.id }?.label,
                                        onReturnToTerminal = returnToTerminal?.takeIf {
                                            connected?.hostId == selected.id
                                        },
                                    )
                                }
                            }
                        },
                    )
                } else {
                    HostListPane(
                        hosts = hosts,
                        selectedId = selectedId,
                        onSelect = { selectedId = it.id },
                        onAdd = { nav.navigate("edit/0") },
                        onEdit = { nav.navigate("edit/${it.id}") },
                        onDelete = {
                            vm.deleteHost(it)
                            if (selectedId == it.id) selectedId = null
                        },
                        onConnect = { host -> requestNotifThenConnect(host) },
                        connectedLabel = connected?.label,
                        onReturnToTerminal = returnToTerminal,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            composable(
                "edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val initial = if (id == 0L) null else hosts.find { it.id == id }
                HostEditScreen(
                    initial = initial,
                    onSave = { entity ->
                        vm.saveHost(entity) { savedId ->
                            selectedId = savedId
                            nav.popBackStack()
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }

            composable(
                "terminal/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val host = hosts.find { it.id == id }
                val title = when (val c = conn) {
                    is ConnectionState.Connected -> c.label
                    else -> host?.name ?: stringResource(R.string.terminal)
                }
                TerminalScreen(
                    title = title,
                    terminalBuffer = vm.terminalBuffer,
                    onWrite = { vm.writeTerminal(it) },
                    onWriteBytes = { vm.writeTerminalBytes(it) },
                    onDisconnect = {
                        vm.disconnect()
                        nav.popBackStack("home", inclusive = false)
                    },
                    onOpenSftp = { nav.navigate("sftp") },
                    onBack = {
                        // Leave terminal screen without dropping the SSH session.
                        nav.popBackStack()
                    },
                )
            }

            composable("sftp") {
                SftpScreen(
                    sshManager = vm.getSshManager(),
                    onBack = { nav.popBackStack() },
                )
            }
        }

        if (conn is ConnectionState.Connecting) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

private fun navigateToConnectedTerminal(nav: NavHostController, conn: ConnectionState) {
    val c = conn as? ConnectionState.Connected ?: return
    val route = "terminal/${c.hostId}"
    val current = nav.currentDestination?.route
    if (current?.startsWith("terminal") == true) return
    nav.navigate(route) { launchSingleTop = true }
}

private fun doConnect(
    vm: HostViewModel,
    host: HostEntity,
    context: android.content.Context,
    onSuccess: () -> Unit,
) {
    vm.connect(host) { result ->
        result.onSuccess {
            onSuccess()
        }.onFailure { e ->
            Toast.makeText(
                context,
                e.message ?: context.getString(R.string.connection_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

@Composable
private fun HostQuickDetail(
    host: HostEntity,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    connectedLabel: String? = null,
    onReturnToTerminal: (() -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text(host.name, style = MaterialTheme.typography.headlineMedium)
        Text(
            "${host.username}@${host.host}:${host.port}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            if (host.useKeyAuth) stringResource(R.string.auth_key) else stringResource(R.string.auth_password),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        androidx.compose.foundation.layout.Row(
            Modifier.padding(top = 24.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            if (connectedLabel != null && onReturnToTerminal != null) {
                androidx.compose.material3.Button(onClick = onReturnToTerminal) {
                    Text(stringResource(R.string.return_to_terminal))
                }
            } else {
                androidx.compose.material3.Button(onClick = onConnect) {
                    Text(stringResource(R.string.connect))
                }
            }
            androidx.compose.material3.OutlinedButton(onClick = onEdit) {
                Text(stringResource(R.string.edit_host))
            }
        }
    }
}

@Composable
private fun HostKeyDialog(prompt: HostKeyDecision?, onResolve: (Boolean) -> Unit) {
    if (prompt == null) return
    val (title, body) = when (prompt) {
        is HostKeyDecision.Unknown ->
            stringResource(R.string.host_key_unknown) to
                "${stringResource(R.string.fingerprint)}\n${prompt.fingerprint}\n\n${prompt.hostPort}\n${prompt.algorithm}"
        is HostKeyDecision.Changed ->
            stringResource(R.string.host_key_changed) to
                "旧: ${prompt.oldFingerprint}\n新: ${prompt.fingerprint}\n\n${prompt.hostPort}"
    }
    AlertDialog(
        onDismissRequest = { onResolve(false) },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onResolve(true) }) {
                Text(stringResource(R.string.trust_key))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(false) }) {
                Text(stringResource(R.string.reject_key))
            }
        },
    )
}
