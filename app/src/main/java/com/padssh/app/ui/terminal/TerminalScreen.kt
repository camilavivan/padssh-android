package com.padssh.app.ui.terminal

import android.content.Context
import android.view.KeyEvent as AndroidKeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.padssh.app.MainActivity
import com.padssh.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private val TermBg = Color(0xFF0D1117)
private val TermFg = Color(0xFFE6EDF3)
private val TermBarBg = Color(0xFF21262D)
private val TermAccent = Color(0xFF58A6FF)

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
    var ctrlHeld by remember { mutableStateOf(false) }
    var flashedKey by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val context = LocalContext.current
    val activity = context as? MainActivity
    val editTextRef = remember { mutableStateOf<TerminalEditText?>(null) }

    val onWriteState = rememberUpdatedState(onWrite)
    val onWriteBytesState = rememberUpdatedState(onWriteBytes)
    val ctrlHeldState = rememberUpdatedState(ctrlHeld)

    // System / gesture back: leave terminal once, never empty the NavHost.
    BackHandler(onBack = onBack)

    LaunchedEffect(buffer) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    LaunchedEffect(flashedKey) {
        if (flashedKey != null) {
            delay(150)
            flashedKey = null
        }
    }

    // Activity-level Bluetooth / hardware key handler. Must map keys itself
    // (not only via EditText) so Enter never reaches a focused TopAppBar button
    // when editTextRef is null or Compose stole focus.
    DisposableEffect(activity) {
        val handler: (AndroidKeyEvent) -> Boolean = handler@{ event ->
            val mapped = TerminalKeyMapper.map(
                event = event,
                ctrlHeld = ctrlHeldState.value,
                onWrite = onWriteState.value,
                onWriteBytes = onWriteBytesState.value,
                onCtrlHeldChange = { ctrlHeld = it },
            )
            if (mapped) return@handler true
            // Hard-consume Enter variants even if mapper returned false.
            if (TerminalKeyMapper.mustConsume(event.keyCode)) return@handler true
            false
        }
        activity?.terminalKeyHandler = handler
        onDispose {
            if (activity?.terminalKeyHandler === handler) {
                activity.terminalKeyHandler = null
            }
        }
    }

    fun focusInput(showIme: Boolean) {
        val et = editTextRef.value ?: return
        et.requestFocus()
        if (showIme) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun accessoryWrite(label: String, block: () -> Unit) {
        flashedKey = label
        block()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    // Non-focusable back: Icon + clickable, not IconButton.
                    // KEYCODE_ENTER must never activate this control.
                    val backInteraction = remember { MutableInteractionSource() }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cancel),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clickable(
                                interactionSource = backInteraction,
                                indication = androidx.compose.material3.ripple(bounded = false),
                                role = Role.Button,
                                onClick = onBack,
                            )
                            .semantics { role = Role.Button }
                            .focusProperties { canFocus = false },
                    )
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
                .navigationBarsPadding(),
        ) {
            // Output: vertical scroll only; tap on same modifier chain focuses input.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Text(
                    text = buffer.ifEmpty { " " },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                focusInput(showIme = true)
                            }
                        }
                        .padding(8.dp),
                    style = TextStyle(
                        color = TermFg,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                    softWrap = true,
                )
            }

            AccessoryBar(
                ctrlHeld = ctrlHeld,
                flashedKey = flashedKey,
                onToggleCtrl = {
                    accessoryWrite("Ctrl") { ctrlHeld = !ctrlHeld }
                },
                onEsc = {
                    accessoryWrite("Esc") { onWriteBytes(byteArrayOf(0x1B)) }
                },
                onTab = {
                    accessoryWrite("Tab") { onWrite("\t") }
                },
                onCtrlC = {
                    accessoryWrite("Ctrl-C") {
                        onWriteBytes(byteArrayOf(0x03))
                        ctrlHeld = false
                    }
                },
                onUp = {
                    accessoryWrite("↑") {
                        onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()))
                    }
                },
                onDown = {
                    accessoryWrite("↓") {
                        onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte()))
                    }
                },
                onLeft = {
                    accessoryWrite("←") {
                        onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte()))
                    }
                },
                onRight = {
                    accessoryWrite("→") {
                        onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte()))
                    }
                },
            )

            AndroidView(
                factory = { ctx ->
                    TerminalEditText.createConfigured(ctx).also { et ->
                        et.onWrite = onWrite
                        et.onWriteBytes = onWriteBytes
                        et.ctrlHeldProvider = { ctrlHeld }
                        et.onCtrlHeldChange = { ctrlHeld = it }
                        editTextRef.value = et
                        et.post { et.requestFocus() }
                    }
                },
                update = { et ->
                    et.onWrite = onWrite
                    et.onWriteBytes = onWriteBytes
                    et.ctrlHeldProvider = { ctrlHeld }
                    et.onCtrlHeldChange = { ctrlHeld = it }
                    editTextRef.value = et
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            )
        }
    }
}

@Composable
private fun AccessoryBar(
    ctrlHeld: Boolean,
    flashedKey: String?,
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
            .background(TermBarBg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Literal "Tab" — never stringResource(R.string.tab) (can become「标签页」).
        AccessoryKey("Esc", onEsc, emphasized = flashedKey == "Esc")
        AccessoryKey("Tab", onTab, emphasized = flashedKey == "Tab")
        AccessoryKey(
            label = "Ctrl",
            onClick = onToggleCtrl,
            emphasized = ctrlHeld || flashedKey == "Ctrl",
        )
        AccessoryKey("Ctrl-C", onCtrlC, emphasized = flashedKey == "Ctrl-C")
        AccessoryKey("↑", onUp, emphasized = flashedKey == "↑")
        AccessoryKey("↓", onDown, emphasized = flashedKey == "↓")
        AccessoryKey("←", onLeft, emphasized = flashedKey == "←")
        AccessoryKey("→", onRight, emphasized = flashedKey == "→")
    }
}

@Composable
private fun AccessoryKey(
    label: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .focusProperties { canFocus = false },
    ) {
        Text(
            text = label,
            color = if (emphasized) TermAccent else TermFg,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}
