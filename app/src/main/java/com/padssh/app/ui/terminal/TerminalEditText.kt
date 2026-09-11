package com.padssh.app.ui.terminal

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.core.content.ContextCompat

/**
 * Native EditText used as the real input surface for the SSH terminal.
 * Soft IME (commitText / composing) and hardware keys both map to PTY writes.
 */
class TerminalEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    var onWrite: ((String) -> Unit)? = null
    var onWriteBytes: ((ByteArray) -> Unit)? = null
    var ctrlHeldProvider: (() -> Boolean)? = null
    var onCtrlHeldChange: ((Boolean) -> Unit)? = null

    /** Optional Activity-level fallback uses the same mapping. */
    fun handleHardwareKey(event: KeyEvent): Boolean =
        mapKeyEvent(event)

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (mapKeyEvent(event)) return true
        return super.onKeyPreIme(keyCode, event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Consume terminal keys (esp. Enter) before super so they never click UI.
        if (mapKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Keep a real text InputConnection so soft keyboards / CJK IME work.
        outAttrs.inputType = inputType
        outAttrs.imeOptions = EditorInfo.IME_ACTION_SEND or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI

        val base: InputConnection = super.onCreateInputConnection(outAttrs)
            ?: BaseInputConnection(this, true)

        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    sendCommitted(text.toString())
                }
                // Clear local buffer — terminal echo comes from the PTY.
                post { setText("") }
                return true
            }

            override fun finishComposingText(): Boolean {
                val current = text?.toString().orEmpty()
                if (current.isNotEmpty()) {
                    sendCommitted(current)
                    post { setText("") }
                }
                return super.finishComposingText()
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    repeat(beforeLength) {
                        onWriteBytes?.invoke(byteArrayOf(0x7F))
                    }
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (mapKeyEvent(event)) return true
                return super.sendKeyEvent(event)
            }
        }
    }

    private fun sendCommitted(raw: String) {
        val ctrl = ctrlHeldProvider?.invoke() == true
        if (ctrl && raw.length == 1) {
            val c = raw[0].lowercaseChar()
            if (c in 'a'..'z') {
                onWriteBytes?.invoke(byteArrayOf((c.code - 96).toByte()))
                onCtrlHeldChange?.invoke(false)
                return
            }
        }
        val normalized = raw.replace("\n", "\r")
        onWrite?.invoke(normalized)
        if (ctrl) onCtrlHeldChange?.invoke(false)
    }

    private fun mapKeyEvent(event: KeyEvent): Boolean {
        val write = onWrite ?: return TerminalKeyMapper.mustConsume(event.keyCode)
        val writeBytes = onWriteBytes ?: return TerminalKeyMapper.mustConsume(event.keyCode)
        return TerminalKeyMapper.map(
            event = event,
            ctrlHeld = ctrlHeldProvider?.invoke() == true,
            onWrite = write,
            onWriteBytes = writeBytes,
            onCtrlHeldChange = onCtrlHeldChange,
        )
    }

    companion object {
        fun createConfigured(context: Context): TerminalEditText {
            return TerminalEditText(context).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                isSingleLine = false
                maxLines = 3
                hint = "输入命令…"
                setHintTextColor(0xFF8B949E.toInt())
                setTextColor(0xFFE6EDF3.toInt())
                setBackgroundColor(0xFF161B22.toInt())
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 14f
                setPadding(24, 20, 24, 20)
                imeOptions = EditorInfo.IME_ACTION_SEND or
                    EditorInfo.IME_FLAG_NO_FULLSCREEN or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI
                importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
                isFocusable = true
                isFocusableInTouchMode = true
                // Avoid system accent highlight stealing focus visuals on tablets.
                highlightColor = ContextCompat.getColor(context, android.R.color.transparent)
                setOnEditorActionListener { _, actionId, event ->
                    if (actionId == EditorInfo.IME_ACTION_SEND ||
                        (event != null &&
                            event.action == KeyEvent.ACTION_DOWN &&
                            (event.keyCode == KeyEvent.KEYCODE_ENTER ||
                                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER))
                    ) {
                        onWrite?.invoke("\r")
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }
}
