package com.mapmouse.pro.ui

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import com.mapmouse.pro.model.Keys

/**
 * Container for in-game panels that can wait for "the next key": keyboard keys (by scan
 * code), mouse buttons and the wheel. The window must be focusable and mapping paused, so
 * the keyboard and mouse reach Android instead of the input server.
 */
open class KeyCaptureLayout(context: Context) : FrameLayout(context) {
    private var listening: ((Int) -> Unit)? = null
    private var listenButton: Button? = null
    private var restore: (() -> Unit)? = null

    /** Called for Back when nothing is being captured. */
    var onBack: (() -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    /** Turns [button] into "Press a key…" until a key/button/wheel step arrives. */
    fun listen(button: Button, onKey: (Int) -> Unit) {
        restore?.invoke()
        val oldText = button.text
        val oldBg = button.background
        val oldColor = button.currentTextColor
        restore = { button.text = oldText; button.background = oldBg; button.setTextColor(oldColor) }
        listenButton = button
        button.text = "Press a key…"
        button.background = roundBg(0xFFF2453D.toInt(), dp(6f).toFloat())
        button.setTextColor(0xFFFFFFFF.toInt())
        listening = onKey
        requestFocus()
    }

    private fun deliver(code: Int) {
        val l = listening ?: return
        listening = null
        restore?.invoke(); restore = null
        listenButton = null
        l(code)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (listening == null) {
            if (findFocus() is EditText) return super.dispatchKeyEvent(event)
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP &&
                !event.isFromSource(InputDevice.SOURCE_MOUSE)) onBack?.invoke()
            return if (event.keyCode == KeyEvent.KEYCODE_BACK) true else super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        var code = event.scanCode
        if (code <= 0) {
            // Mouse right-click arrives as a synthetic BACK without a scan code: ignore it here.
            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true
            code = Keys.fromAndroidKeyCode(event.keyCode)
        }
        if (code > 0) deliver(code)
        return true
    }

    private fun mouseButtonCode(e: MotionEvent): Int {
        val b = if (e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) e.actionButton else e.buttonState
        return when {
            (b and MotionEvent.BUTTON_PRIMARY) != 0 -> Keys.BTN_LEFT
            (b and MotionEvent.BUTTON_SECONDARY) != 0 -> Keys.BTN_RIGHT
            (b and MotionEvent.BUTTON_TERTIARY) != 0 -> Keys.BTN_MIDDLE
            (b and MotionEvent.BUTTON_BACK) != 0 -> Keys.BTN_SIDE
            (b and MotionEvent.BUTTON_FORWARD) != 0 -> Keys.BTN_EXTRA
            else -> Keys.NONE
        }
    }

    private fun interceptMouse(e: MotionEvent): Boolean {
        if (listening == null || !e.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) deliver(if (v > 0) Keys.WHEEL_UP else Keys.WHEEL_DOWN)
            }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                val code = mouseButtonCode(e)
                if (code != Keys.NONE) deliver(code)
            }
        }
        return true
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean = interceptMouse(e) || super.dispatchTouchEvent(e)
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean = interceptMouse(e) || super.dispatchGenericMotionEvent(e)
}

/** White "Edit" button for hotkey rows. */
fun Context.editKeyButton(): Button = Button(this).apply {
    text = "Edit"; isAllCaps = false; textSize = 15f; setTextColor(Pal.ACCENT)
    background = roundBg(0xFFFFFFFF.toInt(), dp(6f).toFloat()); stateListAnimator = null
    minWidth = dp(90f); minimumWidth = dp(90f); minHeight = 0; minimumHeight = 0
    setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
}
