package com.padssh.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padssh.app.R
import kotlinx.coroutines.flow.StateFlow

private val TermBg = Color(0xFF0D1117)
private val TermFg = Color(0xFFE6EDF3)

/** Strip common CSI / OSC sequences for a readable MVP terminal. */
fun stripAnsi(input: String): String {
    return input
        .replace(Regex("\u001B\\][^\u0007]*\u0007"), "")
        .replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "")
        .replace(Regex("\u001B[()][0-9A-Za-z]"), "")
        .replace(Regex("\u001B."), "")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    title: String,
    terminalBuffer: StateFlow<String>,
    onWrite: (String) -> Unit,
    onWriteBytes: (ByteArray) -> Unit,
    onDisconnect: () -> Unit,
    onOpenSftp: () -> Unit,
    onBack: () -> Unit,
) {
    val rawBuffer by terminalBuffer.collectAsState()
    val buffer = remember(rawBuffer) { stripAnsi(rawBuffer) }
    var input by remember { mutableStateOf("") }
    var ctrlHeld by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    LaunchedEffect(buffer) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSftp) {
                        Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.sftp))
                    }
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.Default.LinkOff, contentDescription = stringResource(R.string.disconnect))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(TermBg),
        ) {
            Text(
                text = buffer.ifEmpty { " " },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
                style = TextStyle(
                    color = TermFg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
            )

            AccessoryBar(
                ctrlHeld = ctrlHeld,
                onToggleCtrl = { ctrlHeld = !ctrlHeld },
                onEsc = { onWriteBytes(byteArrayOf(0x1B)) },
                onTab = { onWrite("\t") },
                onCtrlC = { onWriteBytes(byteArrayOf(0x03)); ctrlHeld = false },
                onUp = { onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())) },
                onDown = { onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte())) },
                onRight = { onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte())) },
                onLeft = { onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte())) },
            )

            BasicTextField(
                value = input,
                onValueChange = { new ->
                    if (new.length < input.length) {
                        // backspace
                        onWriteBytes(byteArrayOf(0x7F))
                        input = new
                        return@BasicTextField
                    }
                    val added = new.removePrefix(input)
                    if (added.isNotEmpty()) {
                        if (ctrlHeld && added.length == 1) {
                            val c = added[0].lowercaseChar()
                            if (c in 'a'..'z') {
                                onWriteBytes(byteArrayOf((c.code - 96).toByte()))
                                ctrlHeld = false
                            } else {
                                onWrite(added)
                            }
                        } else {
                            onWrite(added)
                        }
                    }
                    input = ""
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .padding(12.dp),
                textStyle = TextStyle(
                    color = TermFg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(TermFg),
                singleLine = true,
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text(
                            "输入命令…",
                            color = Color(0xFF8B949E),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                        )
                    }
                    inner()
                },
            )
        }
    }
}

@Composable
private fun AccessoryBar(
    ctrlHeld: Boolean,
    onToggleCtrl: () -> Unit,
    onEsc: () -> Unit,
    onTab: () -> Unit,
    onCtrlC: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF21262D))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(selected = false, onClick = onEsc, label = { Text(stringResource(R.string.esc)) })
        FilterChip(selected = false, onClick = onTab, label = { Text(stringResource(R.string.tab)) })
        FilterChip(
            selected = ctrlHeld,
            onClick = onToggleCtrl,
            label = { Text(stringResource(R.string.ctrl)) },
        )
        FilterChip(selected = false, onClick = onCtrlC, label = { Text(stringResource(R.string.ctrl_c)) })
        FilterChip(selected = false, onClick = onUp, label = { Text("↑") })
        FilterChip(selected = false, onClick = onDown, label = { Text("↓") })
        FilterChip(selected = false, onClick = onLeft, label = { Text("←") })
        FilterChip(selected = false, onClick = onRight, label = { Text("→") })
    }
}
