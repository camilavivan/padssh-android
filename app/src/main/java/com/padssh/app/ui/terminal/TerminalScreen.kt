package com.padssh.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padssh.app.R
import kotlinx.coroutines.flow.StateFlow

private val TermBg = Color(0xFF0D1117)
private val TermFg = Color(0xFFE6EDF3)
private val TermInputBg = Color(0xFF161B22)
private val TermBarBg = Color(0xFF21262D)
private val TermHint = Color(0xFF8B949E)

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

/**
 * Map hardware / accessory key events to SSH PTY bytes.
 * Returns true when the event was consumed (must not activate TopAppBar).
 */
internal fun handleTerminalKeyEvent(
    event: KeyEvent,
    ctrlHeld: Boolean,
    onCtrlHeldChange: (Boolean) -> Unit,
    onWrite: (String) -> Unit,
    onWriteBytes: (ByteArray) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) {
        // Consume KeyUp for keys we care about so they don't bubble to navigation.
        return when (event.key) {
            Key.Enter, Key.NumPadEnter, Key.Backspace, Key.Delete,
            Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
            Key.Tab, Key.Escape,
            -> true
            else -> {
                val cp = event.utf16CodePoint
                cp != 0 && (cp in 1..26 || !cp.toChar().isISOControl())
            }
        }
    }

    val ctrl = event.isCtrlPressed || ctrlHeld

    fun clearSoftCtrl() {
        if (ctrlHeld) onCtrlHeldChange(false)
    }

    when (event.key) {
        Key.Enter, Key.NumPadEnter -> {
            onWrite("\r")
            return true
        }
        Key.Backspace -> {
            onWriteBytes(byteArrayOf(0x7F))
            return true
        }
        Key.Delete -> {
            onWriteBytes(byteArrayOf(0x7F))
            return true
        }
        Key.DirectionUp -> {
            onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()))
            return true
        }
        Key.DirectionDown -> {
            onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte()))
            return true
        }
        Key.DirectionRight -> {
            onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte()))
            return true
        }
        Key.DirectionLeft -> {
            onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte()))
            return true
        }
        Key.Tab -> {
            onWrite("\t")
            return true
        }
        Key.Escape -> {
            onWriteBytes(byteArrayOf(0x1B))
            return true
        }
        else -> {
            // Prefer native unicode (handles Ctrl+letter → 1..26 from Android).
            val native = event.nativeKeyEvent
            val unicode = native.unicodeChar
            val codePoint = when {
                unicode != 0 -> unicode
                event.utf16CodePoint != 0 -> event.utf16CodePoint
                else -> 0
            }
            if (codePoint == 0) return false

            // Already a control byte (e.g. hardware Ctrl+C → 3)
            if (codePoint in 1..26) {
                onWriteBytes(byteArrayOf(codePoint.toByte()))
                clearSoftCtrl()
                return true
            }

            val ch = codePoint.toChar()
            if (ch.isISOControl()) return false

            if (ctrl) {
                val lower = ch.lowercaseChar()
                if (lower in 'a'..'z') {
                    onWriteBytes(byteArrayOf((lower.code - 96).toByte()))
                    clearSoftCtrl()
                    return true
                }
            }

            onWrite(String(Character.toChars(codePoint)))
            return true
        }
    }
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
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(buffer) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    val onKey: (KeyEvent) -> Boolean = remember(ctrlHeld, onWrite, onWriteBytes) {
        { event ->
            handleTerminalKeyEvent(
                event = event,
                ctrlHeld = ctrlHeld,
                onCtrlHeldChange = { ctrlHeld = it },
                onWrite = onWrite,
                onWriteBytes = onWriteBytes,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.focusProperties { canFocus = false },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenSftp,
                        modifier = Modifier.focusProperties { canFocus = false },
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.sftp))
                    }
                    IconButton(
                        onClick = onDisconnect,
                        modifier = Modifier.focusProperties { canFocus = false },
                    ) {
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
                .background(TermBg)
                .onPreviewKeyEvent(onKey),
        ) {
            // Scrollable output — tap to refocus input for hardware keyboard.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { focusRequester.requestFocus() },
            ) {
                Text(
                    text = buffer.ifEmpty { " " },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp)
                        .focusProperties { canFocus = false },
                    style = TextStyle(
                        color = TermFg,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                )
            }

            AccessoryBar(
                ctrlHeld = ctrlHeld,
                onToggleCtrl = { ctrlHeld = !ctrlHeld },
                onEsc = { onWriteBytes(byteArrayOf(0x1B)) },
                onTab = { onWrite("\t") },
                onCtrlC = {
                    onWriteBytes(byteArrayOf(0x03))
                    ctrlHeld = false
                },
                onUp = { onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())) },
                onDown = { onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte())) },
                onRight = { onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte())) },
                onLeft = { onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte())) },
            )

            // Soft-keyboard path via onValueChange; hardware keys handled by parent onPreviewKeyEvent.
            BasicTextField(
                value = input,
                onValueChange = { new ->
                    if (new.length < input.length) {
                        onWriteBytes(byteArrayOf(0x7F))
                        input = ""
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
                            // Soft IME may insert '\n' for send/go.
                            val normalized = added.replace("\n", "\r")
                            onWrite(normalized)
                        }
                    }
                    input = ""
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TermInputBg)
                    .padding(12.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent(onKey),
                textStyle = TextStyle(
                    color = TermFg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(TermFg),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { onWrite("\r") },
                ),
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text(
                            "输入命令…",
                            color = TermHint,
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
    val noFocus = Modifier.focusProperties { canFocus = false }
    Row(
        Modifier
            .fillMaxWidth()
            .background(TermBarBg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .focusProperties { canFocus = false },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AccessoryKey(stringResource(R.string.esc), onEsc, noFocus)
        AccessoryKey(stringResource(R.string.tab), onTab, noFocus)
        AccessoryKey(
            label = stringResource(R.string.ctrl),
            onClick = onToggleCtrl,
            modifier = noFocus,
            emphasized = ctrlHeld,
        )
        AccessoryKey(stringResource(R.string.ctrl_c), onCtrlC, noFocus)
        AccessoryKey("↑", onUp, noFocus)
        AccessoryKey("↓", onDown, noFocus)
        AccessoryKey("←", onLeft, noFocus)
        AccessoryKey("→", onRight, noFocus)
    }
}

@Composable
private fun AccessoryKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.focusProperties { canFocus = false },
    ) {
        Text(
            text = label,
            color = if (emphasized) Color(0xFF58A6FF) else TermFg,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}
