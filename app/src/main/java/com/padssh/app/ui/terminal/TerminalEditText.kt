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
        // Only act on ACTION_DOWN for writes; consume matching ACTION_UP.
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isUp = event.action == KeyEvent.ACTION_UP

        fun consumeUp(): Boolean = isUp

        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (isDown) onWrite?.invoke("\r")
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_DEL -> {
                if (isDown) onWriteBytes?.invoke(byteArrayOf(0x7F))
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (isDown) {
                    onWriteBytes?.invoke(
                        byteArrayOf(0x1B, '['.code.toByte(), '3'.code.toByte(), '~'.code.toByte()),
                    )
                }
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_TAB -> {
                if (isDown) onWrite?.invoke("\t")
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (isDown) onWriteBytes?.invoke(byteArrayOf(0x1B))
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isDown) {
                    onWriteBytes?.invoke(
                        byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()),
                    )
                }
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isDown) {
                    onWriteBytes?.invoke(
                        byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte()),
                    )
                }
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isDown) {
                    onWriteBytes?.invoke(
                        byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte()),
                    )
                }
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isDown) {
                    onWriteBytes?.invoke(
                        byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte()),
                    )
                }
                return isDown || consumeUp()
            }
        }

        if (!isDown) {
            // Consume KeyUp for printable / ctrl letters we handled on KeyDown.
            val unicode = event.unicodeChar
            return unicode != 0 && (unicode in 1..26 || !Character.isISOControl(unicode))
        }

        val ctrlHeld = ctrlHeldProvider?.invoke() == true
        val ctrl = event.isCtrlPressed || ctrlHeld

        // Prefer unicodeChar (already maps Ctrl+C → 3 when hardware Ctrl is held).
        val unicode = event.unicodeChar
        if (unicode in 1..26) {
            onWriteBytes?.invoke(byteArrayOf(unicode.toByte()))
            if (ctrlHeld) onCtrlHeldChange?.invoke(false)
            return true
        }

        if (unicode != 0 && !Character.isISOControl(unicode)) {
            val ch = unicode.toChar()
            if (ctrl) {
                val lower = ch.lowercaseChar()
                if (lower in 'a'..'z') {
                    onWriteBytes?.invoke(byteArrayOf((lower.code - 96).toByte()))
                    if (ctrlHeld) onCtrlHeldChange?.invoke(false)
                    return true
                }
            }
            onWrite?.invoke(String(Character.toChars(unicode)))
            if (ctrlHeld) onCtrlHeldChange?.invoke(false)
            return true
        }

        // Soft-Ctrl + letter when unicodeChar is plain letter without META_CTRL.
        if (ctrlHeld && !event.isCtrlPressed) {
            val ch = event.displayLabel.lowercaseChar()
            if (ch in 'a'..'z') {
                onWriteBytes?.invoke(byteArrayOf((ch.code - 96).toByte()))
                onCtrlHeldChange?.invoke(false)
                return true
            }
        }

        return false
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
                // Avoid system accent highlight stealing focus visuals on tablets.
                highlightColor = ContextCompat.getColor(context, android.R.color.transparent)
            }
        }
    }
}
