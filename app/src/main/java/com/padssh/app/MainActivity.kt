package com.padssh.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.padssh.app.data.HostEntity
import com.padssh.app.ssh.ConnectionState
import com.padssh.app.ssh.HostKeyDecision
import com.padssh.app.ui.hosts.HostDetailPlaceholder
import com.padssh.app.ui.hosts.HostEditScreen
import com.padssh.app.ui.hosts.HostListPane
import com.padssh.app.ui.hosts.TabletHostLayout
import com.padssh.app.ui.sftp.SftpScreen
import com.padssh.app.ui.terminal.TerminalScreen
import com.padssh.app.ui.theme.PadSshTheme
import com.padssh.app.viewmodel.HostViewModel

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PadSshApplication
        setContent {
            PadSshTheme {
                val widthClass = calculateWindowSizeClass(this).widthSizeClass
                val vm: HostViewModel = viewModel(
                    factory = HostViewModel.Factory(app.repository, app.sshManager),
                )
                PadSshNav(vm, isExpanded = widthClass != WindowWidthSizeClass.Compact)
            }
        }
    }
}

@Composable
private fun PadSshNav(vm: HostViewModel, isExpanded: Boolean) {
    val nav = rememberNavController()
    val hosts by vm.hosts.collectAsState()
    val conn by vm.connectionState.collectAsState()
    val prompt by vm.hostKeyPrompt.collectAsState()
    val context = LocalContext.current
    var selectedId by remember { mutableStateOf<Long?>(null) }

    HostKeyDialog(prompt) { trust -> vm.resolveHostKey(trust) }

    if (conn is ConnectionState.Connecting) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            val selected = hosts.find { it.id == selectedId }
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
                            onConnect = { host ->
                                vm.connect(host) { result ->
                                    result.onSuccess {
                                        nav.navigate("terminal/${host.id}")
                                    }.onFailure { e ->
                                        Toast.makeText(
                                            context,
                                            e.message ?: context.getString(R.string.connection_failed),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            },
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
                                    onConnect = {
                                        vm.connect(selected) { result ->
                                            result.onSuccess {
                                                nav.navigate("terminal/${selected.id}")
                                            }.onFailure { e ->
                                                Toast.makeText(
                                                    context,
                                                    e.message ?: context.getString(R.string.connection_failed),
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }
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
                    onConnect = { host ->
                        vm.connect(host) { result ->
                            result.onSuccess {
                                nav.navigate("terminal/${host.id}")
                            }.onFailure { e ->
                                Toast.makeText(
                                    context,
                                    e.message ?: context.getString(R.string.connection_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
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
                outputFlow = vm.terminalOutput,
                onWrite = { vm.writeTerminal(it) },
                onWriteBytes = { vm.writeTerminalBytes(it) },
                onDisconnect = {
                    vm.disconnect()
                    nav.popBackStack("home", inclusive = false)
                },
                onOpenSftp = { nav.navigate("sftp") },
                onBack = {
                    vm.disconnect()
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
}

@Composable
private fun HostQuickDetail(
    host: HostEntity,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
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
            androidx.compose.material3.Button(onClick = onConnect) {
                Text(stringResource(R.string.connect))
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
