package com.padssh.app.ui.terminal

import android.view.KeyEvent

/**
 * Shared hardware / Bluetooth key → PTY mapping used by [TerminalEditText]
 * and the Activity-level fallback in [TerminalScreen].
 *
 * Enter / NumpadEnter / DpadCenter are always consumed so they never activate
 * focused Compose buttons (e.g. TopAppBar back).
 */
object TerminalKeyMapper {

    fun map(
        event: KeyEvent,
        ctrlHeld: Boolean,
        onWrite: (String) -> Unit,
        onWriteBytes: (ByteArray) -> Unit,
        onCtrlHeldChange: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isUp = event.action == KeyEvent.ACTION_UP

        fun consumeUp(): Boolean = isUp

        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> {
                if (isDown) onWrite("\r")
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_DEL -> {
                if (isDown) onWriteBytes(byteArrayOf(0x7F))
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (isDown) {
                    onWriteBytes(
                        byteArrayOf(0x1B, '['.code.toByte(), '3'.code.toByte(), '~'.code.toByte()),
                    )
                }
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_TAB -> {
                if (isDown) onWrite("\t")
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (isDown) onWriteBytes(byteArrayOf(0x1B))
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isDown) {
                    onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()))
                }
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isDown) {
                    onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte()))
                }
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isDown) {
                    onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte()))
                }
                return isDown || consumeUp()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isDown) {
                    onWriteBytes(byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte()))
                }
                return isDown || consumeUp()
            }
        }

        if (!isDown) {
            val unicode = event.unicodeChar
            return unicode != 0 && (unicode in 1..26 || !Character.isISOControl(unicode))
        }

        val ctrl = event.isCtrlPressed || ctrlHeld

        val unicode = event.unicodeChar
        if (unicode in 1..26) {
            onWriteBytes(byteArrayOf(unicode.toByte()))
            if (ctrlHeld) onCtrlHeldChange?.invoke(false)
            return true
        }

        if (unicode != 0 && !Character.isISOControl(unicode)) {
            val ch = unicode.toChar()
            if (ctrl) {
                val lower = ch.lowercaseChar()
                if (lower in 'a'..'z') {
                    onWriteBytes(byteArrayOf((lower.code - 96).toByte()))
                    if (ctrlHeld) onCtrlHeldChange?.invoke(false)
                    return true
                }
            }
            onWrite(String(Character.toChars(unicode)))
            if (ctrlHeld) onCtrlHeldChange?.invoke(false)
            return true
        }

        if (ctrlHeld && !event.isCtrlPressed) {
            val ch = event.displayLabel.lowercaseChar()
            if (ch in 'a'..'z') {
                onWriteBytes(byteArrayOf((ch.code - 96).toByte()))
                onCtrlHeldChange?.invoke(false)
                return true
            }
        }

        return false
    }

    /** Keys that must never reach focused UI buttons while the terminal is visible. */
    fun mustConsume(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER,
        -> true
        else -> false
    }
}
