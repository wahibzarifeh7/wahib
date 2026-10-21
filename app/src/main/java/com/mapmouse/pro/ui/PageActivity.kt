package com.mapmouse.pro.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mapmouse.pro.AppSettings
import com.mapmouse.pro.ServerConnection
import com.mapmouse.pro.Store
import com.mapmouse.pro.model.Keys
import com.mapmouse.pro.model.Profile
import com.mapmouse.pro.service.OverlayService

/** Base for the app's settings screens: a dark scrolling column built in code. */
abstract class PageActivity : Activity() {
    protected lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24f))
        }
        titleBar = FrameLayout(this).apply { setBackgroundColor(Pal.BAR) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.BG)
            addView(titleBar, LinearLayout.LayoutParams(-1, dp(56f)))
            addView(View(context).apply { setBackgroundColor(Pal.LINE) }, LinearLayout.LayoutParams(-1, dp(1f)))
            addView(ScrollView(context).apply { addView(root) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        window.statusBarColor = Pal.BAR
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Rebuilds the whole page. */
    abstract fun build()

    fun refresh() {
        if (!::root.isInitialized) return
        root.removeAllViews()
        build()
    }

    protected lateinit var titleBar: FrameLayout

    /** Centered title in the top bar, with a "<" back button on sub-pages. */
    protected fun title(text: String, back: Boolean = true) {
        titleBar.removeAllViews()
        titleBar.addView(TextView(this).apply {
            this.text = text; textSize = 20f; setTextColor(Pal.INK); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        if (back) titleBar.addView(TextView(this).apply {
            this.text = "<"; textSize = 24f; setTextColor(Pal.INK); gravity = Gravity.CENTER
            setPadding(dp(18f), 0, dp(18f), 0)
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(-2, -1, Gravity.START))
    }

    /** Section: an accent-colored header strip, then a padded body. */
    protected fun section(title: String, hint: String? = null): LinearLayout {
        root.addView(ggHeader(title).apply { setPadding(dp(16f), dp(10f), dp(16f), dp(10f)) })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(12f))
        }
        if (hint != null) box.addView(label(hint, 13f, Pal.SUB))
        root.addView(box)
        return box
    }

    protected fun status(ok: Boolean, text: String) = label((if (ok) "✔  " else "✖  ") + text, 15f,
        if (ok) Pal.OK else Pal.BAD)

    protected fun scroll(vararg v: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(hrow(*v))
    }

    protected fun toggleChip(title: String, get: () -> Boolean, set: (Boolean) -> Unit): Button {
        val b = chip("") {}
        fun label() { b.text = (if (get()) "☑ " else "☐ ") + title }
        label()
        b.setOnClickListener { set(!get()); label() }
        return b
    }

    protected fun cycleChip(title: String, values: List<String>, index: () -> Int, set: (Int) -> Unit): Button {
        val b = chip("$title: ${values[index().coerceIn(0, values.size - 1)]}") {}
        b.setOnClickListener {
            val n = (index() + 1) % values.size
            set(n); b.text = "$title: ${values[n]}"
        }
        return b
    }

    protected fun keyChip(title: String, get: () -> Int, set: (Int) -> Unit): View =
        chip("$title: ${Keys.name(get())}") { b ->
            pickKey(title) { code -> set(code); b.text = "$title: ${Keys.name(code)}" }
        }

    /** Waits for the next key press; mouse buttons, wheel and gamepad are offered as buttons. */
    protected fun pickKey(title: String, onPick: (Int) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }
        box.addView(label("Press a key on your keyboard, or choose:", 14f, Pal.INK))
        lateinit var dialog: AlertDialog
        val pick = { code: Int -> onPick(code); dialog.dismiss() }
        val mouse = listOf(Keys.BTN_LEFT, Keys.BTN_RIGHT, Keys.BTN_MIDDLE, Keys.BTN_SIDE, Keys.BTN_EXTRA,
            Keys.WHEEL_UP, Keys.WHEEL_DOWN)
        mouse.chunked(4).forEach { row -> box.addView(hrow(*row.map { c -> chip(Keys.name(c)) { pick(c) } }.toTypedArray())) }
        val pad = listOf(0x130, 0x131, 0x133, 0x134, 0x136, 0x137, Keys.TRIGGER_L, Keys.TRIGGER_R,
            Keys.DPAD_UP, Keys.DPAD_DOWN, Keys.DPAD_LEFT, Keys.DPAD_RIGHT, 0x13d, 0x13e, 0x13a, 0x13b)
        box.addView(label("Gamepad:", 12f))
        pad.chunked(4).forEach { row -> box.addView(hrow(*row.map { c -> chip(Keys.name(c)) { pick(c) } }.toTypedArray())) }
        box.addView(chip("Clear (no key)") { pick(Keys.NONE) })
        dialog = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancel", null)
            .setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.scanCode == 0) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val code = if (event.scanCode > 0) event.scanCode else Keys.fromAndroidKeyCode(keyCode)
                    if (code > 0) pick(code)
                }
                true
            }
            .create()
        dialog.show()
    }

    protected fun prompt(title: String, initial: String, numeric: Boolean = false, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial); setSelection(initial.length); gravity = Gravity.START
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().let(onOk) }
            .setNegativeButton("Cancel", null).show()
    }

    protected fun confirm(msg: String, onOk: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Yes") { _, _ -> onOk() }
            .setNegativeButton("No", null).show()
    }

    protected fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** Saves profile settings and applies them live (no finger reset). */
    protected fun saveSettings(p: Profile) {
        Store.save(this, p)
        if (OverlayService.running) OverlayService.start(this, OverlayService.ACTION_SETTINGS)
        else ServerConnection.updateSettings(p)
    }

    /** Saves global options and pushes them to the server and overlays. */
    protected fun saveOptions() {
        val s = AppSettings.get(this)
        s.save(this)
        if (OverlayService.running) OverlayService.start(this, OverlayService.ACTION_OPTIONS)
        else ServerConnection.setOptions(s.serverOptions())
    }

    protected fun note(box: LinearLayout, text: String) = box.addView(label(text, 12f))

    protected fun TextView.bold() = apply { paint.isFakeBoldText = true }
}
