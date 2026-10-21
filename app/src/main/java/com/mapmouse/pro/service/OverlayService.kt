package com.mapmouse.pro.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mapmouse.pro.AppSettings
import com.mapmouse.pro.R
import com.mapmouse.pro.ServerConnection
import com.mapmouse.pro.Store
import com.mapmouse.pro.model.Profile
import com.mapmouse.pro.ui.CursorView
import com.mapmouse.pro.ui.EditorView
import com.mapmouse.pro.ui.HudView
import com.mapmouse.pro.ui.Icon
import com.mapmouse.pro.ui.IconView
import com.mapmouse.pro.ui.InGameSettings
import com.mapmouse.pro.ui.KeyCaptureLayout
import com.mapmouse.pro.ui.ggHeader
import com.mapmouse.pro.ui.ggSwitchRow
import com.mapmouse.pro.ui.primaryButton
import com.mapmouse.pro.ui.MainActivity
import com.mapmouse.pro.ui.MonitorView
import com.mapmouse.pro.ui.Pal
import com.mapmouse.pro.ui.adjustValueBody
import com.mapmouse.pro.ui.displayRotation
import com.mapmouse.pro.ui.dp
import com.mapmouse.pro.ui.dynamicBody
import com.mapmouse.pro.ui.ggChevron
import com.mapmouse.pro.ui.ggRow
import com.mapmouse.pro.ui.ggSegmentedRow
import com.mapmouse.pro.ui.indigoPanel
import com.mapmouse.pro.ui.overlayType
import com.mapmouse.pro.ui.realScreenSize
import com.mapmouse.pro.ui.recoilDisplay
import com.mapmouse.pro.ui.roundBg
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Foreground service hosting everything shown over games: the logo button at the top
 * (with the recoil pill next to it), the expandable top bar, the in-game panels (settings,
 * configs, recoil adjust), the pointer/HUD layer, the device monitor and the keymap editor.
 * It also pauses mapping outside the game and switches profiles when a known game comes up.
 */
class OverlayService : Service(), ServerConnection.Listener {

    private lateinit var wm: WindowManager
    private lateinit var profile: Profile
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    /** Logo button + recoil pill, top center. */
    private var top: LinearLayout? = null
    private lateinit var logo: FrameLayout
    private lateinit var logoDot: View
    private lateinit var pill: TextView
    private lateinit var pillSpacer: View
    private var logoHidden = false
    /** Expanded top bar. */
    private var bar: View? = null
    /** Panel (settings, configs, adjust value…) currently shown, if any. */
    private var panel: View? = null
    private var cursor: CursorView? = null
    private var hudView: HudView? = null
    private var monitor: MonitorView? = null
    private var editor: EditorView? = null

    private var lastForeground = ""
    /** The app mapping is used in: where mapping was started, or the latest "My games" entry opened. */
    private var gamePkg: String? = null
    /** False while another app is in front (mapping paused, overlays hidden). */
    private var inGame = true
    private var wasEnabled = false
    private var playStart = 0L
    /** Package whose display-size override is currently applied. */
    private var displayFor: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WindowManager::class.java)
        profile = Store.active(this)
        startForegroundCompat()
        addPassThrough()
        addTop()
        syncMonitor()
        ServerConnection.onConnected = { s ->
            val size = realScreenSize()
            s.setScreen(size.x, size.y, displayRotation())
            s.setOptions(AppSettings.get(this).serverOptions().toString())
            s.setProfile(profile.toJson().toString())
            s.setPaused(editor != null || panel != null || !inGame)
        }
        ServerConnection.addListener(this)
        ServerConnection.connect(this)
        main.postDelayed(::watchForeground, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_RELOAD -> {
                profile = Store.active(this)
                ServerConnection.setProfile(profile)
                hudView?.profile = profile
                updatePill()
            }
            ACTION_SETTINGS -> {
                // Only hotkeys/cursor/recoil changed: apply live without lifting fingers.
                profile = Store.active(this)
                ServerConnection.updateSettings(profile)
                updatePill()
            }
            ACTION_OPTIONS -> applyOptions()
            ACTION_LAUNCH -> intent.getStringExtra(EXTRA_PKG)?.let { launchGame(it) }
        }
        ServerConnection.connect(this)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        ServerConnection.setEnabled(false)
        ServerConnection.setPaused(false)
        if (displayFor != null) ServerConnection.setDisplaySize(0, 0, 0)
        ServerConnection.removeListener(this)
        ServerConnection.onConnected = null
        listOfNotNull(bar, panel, top, passThrough, monitor, editor).forEach { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val size = realScreenSize()
        ServerConnection.setScreen(size.x, size.y, displayRotation())
        hudView?.invalidate()
    }

    // ---- server events ----

    override fun onState(state: ServerConnection.State) {
        if (top == null) return
        logoDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(when {
                !state.connected -> 0xFF9E9E9E.toInt()
                state.enabled -> 0xFF34C759.toInt()
                else -> 0xFFFF9F0A.toInt()
            })
            setStroke(dp(1.5f), Color.WHITE)
        }
        if (state.enabled && playStart == 0L) { playStart = System.currentTimeMillis(); monitor?.playStart = playStart }
        if (state.enabled && !wasEnabled) captureGame()
        wasEnabled = state.enabled
        applyVisibility()
        if (bar != null) showBar() // refresh Start/Stop label
    }

    override fun onCursor(x: Int, y: Int, visible: Boolean) {
        cursor?.update(x, y, visible)
    }

    override fun onHud(hud: JSONObject) {
        if (hud.has("toggleBubble")) {
            logoHidden = !logoHidden
            if (logoHidden) { hideBar(); hidePanel() }
            applyVisibility()
            return
        }
        hudView?.hud = hud
        // F1–F12 may have switched slots, Ctrl+wheel changed the value: mirror it here.
        profile.activeWeapon = hud.optInt("weaponIndex", profile.activeWeapon).coerceIn(0, profile.weapons.size - 1)
        profile.activeWeaponObj().v = hud.optDouble("v", profile.activeWeaponObj().v.toDouble()).toFloat()
        updatePill()
    }

    override fun onPointers(xy: IntArray) {
        hudView?.setPointers(xy)
    }

    // ---- windows ----

    private fun params(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, overlayType(), flags, PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    /**
     * WRAP_CONTENT overlay windows can come out far too narrow on some phones (the content gets
     * cut off), so measure the view ourselves and give the window that exact size.
     */
    private fun sizeToContent(v: View, p: WindowManager.LayoutParams) {
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        v.measure(spec, spec)
        p.width = v.measuredWidth
        p.height = v.measuredHeight
    }

    private val touchFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun fullScreenPassThrough() = params(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    ).apply {
        // Android 12+ blocks touches that pass through another app's untouchable windows when
        // their *combined* opacity exceeds 0.8. So: one full-screen window only, below that limit.
        alpha = PASS_THROUGH_ALPHA
    }

    /** The pointer and the HUD share one full-screen window (two would add up past the limit). */
    private var passThrough: View? = null

    private fun addPassThrough() {
        val hud = HudView(this).apply { profile = this@OverlayService.profile; hud = ServerConnection.hud }
        val ptr = CursorView(this)
        val box = FrameLayout(this).apply {
            addView(hud, FrameLayout.LayoutParams(-1, -1))
            addView(ptr, FrameLayout.LayoutParams(-1, -1))
        }
        wm.addView(box, fullScreenPassThrough())
        hudView = hud
        cursor = ptr
        passThrough = box
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun syncMonitor() {
        val st = AppSettings.get(this)
        monitor?.let { runCatching { wm.removeView(it) } }
        monitor = null
        if (!st.monitor) return
        val v = MonitorView(this)
        v.playStart = playStart
        // Never FLAG_NOT_TOUCHABLE: a second pass-through window would push the combined opacity over
        // Android's limit and block touches. "Fixed" only means it can't be dragged.
        val p = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchFlags)
        p.gravity = Gravity.TOP or Gravity.END
        p.x = dp(8f); p.y = dp(40f)
        if (!st.monFixed) {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            v.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = sx - (e.rawX - tx).toInt(); p.y = sy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(v, p)
                    }
                }
                true
            }
        }
        sizeToContent(v, p)
        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            v.measure(spec, spec)
            if (v.measuredWidth != p.width || v.measuredHeight != p.height) {
                p.width = v.measuredWidth; p.height = v.measuredHeight
                runCatching { wm.updateViewLayout(v, p) }
            }
        }
        wm.addView(v, p)
        monitor = v
        applyVisibility()
    }

    private fun applyOptions() {
        ServerConnection.setOptions(AppSettings.get(this).serverOptions())
        top?.alpha = AppSettings.get(this).bubbleAlpha
        syncMonitor()
        hudView?.invalidate(); cursor?.invalidate()
        updatePill()
    }

    // ---- logo button + recoil pill ----

    private fun addTop() {
        val size = dp(54f)
        logoDot = View(this)
        logo = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.ACCENT) }
            elevation = dp(3f).toFloat()
            addView(IconView(context, Icon.MOUSE, Color.WHITE, 24f),
                FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(6f) })
            addView(logoDot, FrameLayout.LayoutParams(dp(11f), dp(11f), Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = dp(6f); bottomMargin = dp(6f)
            })
            setOnClickListener { if (bar == null) showBar() else hideBar() }
        }
        pill = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            background = roundBg(Pal.ACCENT, dp(8f).toFloat())
            setOnClickListener { showAdjust() }
        }
        pillSpacer = View(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(pill, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24f); marginEnd = dp(8f) })
            addView(logo, LinearLayout.LayoutParams(size, size))
            // Same width as the pill, so the logo itself stays centered on screen.
            addView(pillSpacer, LinearLayout.LayoutParams(0, 1).apply { marginStart = dp(8f) })
            alpha = AppSettings.get(this@OverlayService).bubbleAlpha
        }
        val p = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchFlags)
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = -dp(20f) // the logo sits half off the top edge
        topParams = p
        top = box
        updatePill()
        wm.addView(box, p)
    }

    private var topParams: WindowManager.LayoutParams? = null

    /** Keeps the logo centered (spacer as wide as the pill) and the window as big as its content. */
    private fun relayoutTop() {
        val box = top ?: return
        val p = topParams ?: return
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        pill.measure(spec, spec)
        val w = if (pill.visibility == View.VISIBLE) pill.measuredWidth else 0
        pillSpacer.layoutParams = pillSpacer.layoutParams.apply { width = w }
        sizeToContent(box, p)
        if (box.isAttachedToWindow) runCatching { wm.updateViewLayout(box, p) }
    }

    private fun updatePill() {
        if (top == null) return
        val st = AppSettings.get(this)
        val hud = ServerConnection.hud
        val w = profile.activeWeaponObj()
        val stance = when (hud.optInt("stance")) { 1 -> "Crouch"; 2 -> "Prone"; else -> "Stand" }
        val h = recoilDisplay(w.h)
        pill.text = buildString {
            append(w.name).append("  │  ")
            if (!profile.recoilEnabled) append("OFF") else {
                append(recoilDisplay(w.v))
                if (h != 0) append("  H").append(if (h > 0) "+$h" else "$h")
                append("   ").append(stance)
                if (hud.optBoolean("aiming")) append("  AIM")
            }
        }
        pill.alpha = if (profile.recoilEnabled) st.rcAlpha else st.rcAlpha * 0.7f
        pill.visibility = if (st.showRcPanel && hud.optBoolean("rcPanel", true)) View.VISIBLE else View.GONE
        relayoutTop()
    }

    // ---- top bar ----

    private fun barButton(icon: Icon, text: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(12f), dp(8f), dp(12f), dp(6f))
        addView(IconView(context, icon, Color.WHITE, 30f))
        addView(TextView(context).apply {
            this.text = text; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, dp(3f), 0, 0)
        })
        setOnClickListener { onClick() }
    }

    private fun showBar() {
        hideBar()
        val st = ServerConnection.state
        val typing = ServerConnection.hud.optBoolean("typing")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundBg(Pal.OVERLAY, dp(18f).toFloat())
            setPadding(dp(8f), dp(14f), dp(8f), dp(4f))
            if (!st.connected) {
                addView(barButton(Icon.RETURN, "Start server") { hideBar(); openApp() })
            } else {
                addView(barButton(if (st.enabled) Icon.STOP else Icon.PLAY, if (st.enabled) "Stop" else "Start") {
                    ServerConnection.setEnabled(!st.enabled); hideBar()
                })
            }
            addView(barButton(Icon.CONFIGS, "Configs") { hideBar(); showConfigs() })
            addView(barButton(Icon.EDITOR, "Editor") { hideBar(); openEditor() })
            addView(barButton(Icon.SETTINGS, "Settings") { hideBar(); showSettings() })
            if (st.enabled) addView(barButton(Icon.KEYBOARD, if (typing) "Keyboard\nPlaying" else "Keyboard\nTyping") {
                ServerConnection.setTyping(!typing); hideBar()
            })
            addView(barButton(Icon.RETURN, "Return") { hideBar(); openApp() })
            addView(barButton(Icon.POWER, "Exit") { stopSelf() })
        }
        val collapse = TextView(this).apply {
            text = "Collapse ▲"; textSize = 14f; setTextColor(Color.WHITE)
            setPadding(dp(14f), dp(4f), dp(14f), dp(5f))
            background = roundBg(Pal.OVERLAY, dp(14f).toFloat())
            setOnClickListener { hideBar() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            isClickable = true // taps on the bar itself don't close it
            // Explicit WRAP_CONTENT: a vertical LinearLayout defaults children to MATCH_PARENT width,
            // which squeezed the bar down to the width of the "Collapse" tab and hid most buttons.
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(collapse, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f); marginEnd = dp(24f) })
        }
        // Full-screen transparent layer: the bar always gets its full width (auto-sized overlay
        // windows come out too narrow on some phones), and a tap anywhere else closes it.
        val box = FrameLayout(this).apply {
            setOnClickListener { hideBar() }
            addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(18f) })
        }
        val p = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, touchFlags)
        wm.addView(box, p)
        bar = box
        // Keep the logo above the bar.
        top?.let { t -> runCatching { wm.removeView(t); wm.addView(t, topParams) } }
    }

    private fun hideBar() {
        bar?.let { runCatching { wm.removeView(it) } }
        bar = null
    }

    // ---- panels ----

    /**
     * Shows a panel. Panels can capture keys (hotkey "Edit"), so the window is focusable and
     * mapping is paused while one is open: the keyboard and mouse then reach the panel.
     */
    private fun showPanel(view: View, width: Int, height: Int, gravity: Int, dim: Boolean) {
        hidePanel()
        val box = KeyCaptureLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(-1, -1))
            onBack = ::hidePanel
        }
        capture = box
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (dim) flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        val p = params(width, height, flags)
        p.gravity = gravity
        if (dim) p.dimAmount = 0.35f
        if (gravity and Gravity.BOTTOM == Gravity.BOTTOM) { p.x = dp(16f); p.y = dp(16f) }
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        wm.addView(box, p)
        panel = box
        updatePause()
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        capture = null
        updatePause()
    }

    /** The open panel, for hotkey capture. */
    private var capture: KeyCaptureLayout? = null

    private fun listenKey(button: android.widget.Button, onKey: (Int) -> Unit) {
        capture?.listen(button, onKey)
    }

    private fun bigPanelSize(): Pair<Int, Int> {
        val s = realScreenSize()
        return minOf((s.x * 0.72f).toInt(), dp(820f)) to (s.y * 0.86f).toInt()
    }

    private fun saveProfileLive() {
        Store.save(this, profile)
        ServerConnection.updateSettings(profile)
        updatePill()
    }

    private fun saveOptionsLive() {
        AppSettings.get(this).save(this)
        applyOptions()
    }

    private val settings by lazy {
        InGameSettings(this, { profile }, ::saveProfileLive, ::saveOptionsLive, ::screenAdjustBody).also { s ->
            s.listen = ::listenKey
        }
    }

    private fun showSettings() {
        val (root, body) = indigoPanel("Settings", ::hidePanel)
        for ((title, build) in settings.pages()) {
            body.addView(ggRow(title, trailing = ggChevron()) { showSettingsPage(title, build, ::showSettings) })
        }
        val (w, h) = bigPanelSize()
        showPanel(root, w, h, Gravity.CENTER, dim = true)
    }

    /** A settings page; sub-pages it opens return to it. */
    private fun showSettingsPage(title: String, build: (LinearLayout) -> Unit, back: () -> Unit) {
        val (root, body) = indigoPanel(title, ::hidePanel, onBack = back)
        val self = { showSettingsPage(title, build, back) }
        settings.refresh = self
        settings.navigate = { t, b -> showSettingsPage(t, b, self) }
        build(body)
        val (w, h) = bigPanelSize()
        showPanel(root, w, h, Gravity.CENTER, dim = true)
    }

    /** Per-game resolution override for the game in front. */
    private fun screenAdjustBody(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val app = AppSettings.get(this)
        val game = app.games.firstOrNull { it.pkg == gamePkg }
        if (game == null) {
            box.addView(ggRow("Add this game in the app (My Games) to give it its own screen size."))
            return box
        }
        val real = realScreenSize()
        val w = minOf(real.x, real.y); val h = maxOf(real.x, real.y)
        val sel = when { game.width == 0 -> 0; game.width == w * 9 / 10 -> 1; game.width == w * 3 / 4 -> 2; else -> 3 }
        box.addView(ggSegmentedRow("Screen Size for ${game.label}", "Applied while the game is in front, reset when you leave it",
            listOf("Default", "90%", "75%", "Custom (in app)"), sel) { i ->
            when (i) {
                0 -> { game.width = 0; game.height = 0 }
                1 -> { game.width = w * 9 / 10; game.height = h * 9 / 10 }
                2 -> { game.width = w * 3 / 4; game.height = h * 3 / 4 }
            }
            app.save(this)
            if (game.width > 0 || game.density > 0) { ServerConnection.setDisplaySize(game.width, game.height, game.density); displayFor = game.pkg }
            else { ServerConnection.setDisplaySize(0, 0, 0); displayFor = null }
        })
        return box
    }

    // ---- configs ----

    private fun showConfigs() {
        val (root, body) = indigoPanel("Configs", ::hidePanel)
        body.addView(ggHeader("My Keymaps (tap to use, tap the one in use for options)"))
        for (p in Store.profiles(this)) {
            val active = p.id == profile.id
            body.addView(ggRow((if (active) "●  " else "○  ") + p.name,
                "Component count: ${p.controls.size}" + if (active) "  ·  in use" else "",
                trailing = if (active) TextView(this).apply { text = "Options  >"; setTextColor(Color.WHITE); textSize = 15f } else ggChevron()) {
                if (active) showConfigOptions(p.id) else {
                    setProfile(p); showConfigs()
                    Toast.makeText(this, "Config: ${p.name}", Toast.LENGTH_SHORT).show()
                }
            })
        }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, dp(10f))
            addView(primaryButton("Create") {
                val p = Store.create(this@OverlayService, "Config ${Store.profiles(this@OverlayService).size + 1}")
                setProfile(p); showConfigOptions(p.id)
            }.apply { background = roundBg(0xFF7B86FF.toInt(), dp(10f).toFloat()) })
            addView(primaryButton("Import (paste code)") {
                val txt = runCatching {
                    getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this@OverlayService)?.toString()
                }.getOrNull()
                runCatching { Store.importJson(this@OverlayService, txt!!.trim()) }
                    .onSuccess { Toast.makeText(this@OverlayService, "Imported ${it.name}", Toast.LENGTH_SHORT).show(); showConfigs() }
                    .onFailure { Toast.makeText(this@OverlayService, "Copy a config share code first", Toast.LENGTH_SHORT).show() }
            }.apply { background = roundBg(0xFF7B86FF.toInt(), dp(10f).toFloat()) })
        })
        val (w, h) = bigPanelSize()
        showPanel(root, w, h, Gravity.CENTER, dim = true)
    }

    private fun showConfigOptions(id: String) {
        val p = Store.profiles(this).firstOrNull { it.id == id } ?: return showConfigs()
        val (root, body) = indigoPanel("Config: ${p.name}", ::hidePanel, onBack = ::showConfigs)
        body.addView(ggHeader("Config"))
        body.addView(ggRow("Name", trailing = android.widget.EditText(this).apply {
            setText(p.name); setTextColor(Color.WHITE); textSize = 15f; isSingleLine = true; minWidth = dp(220f)
            val commit = { v: android.widget.TextView ->
                val n = v.text.toString().trim()
                if (n.isNotEmpty() && n != p.name) { p.name = n; Store.save(this@OverlayService, p); if (p.id == profile.id) profile.name = n }
            }
            setOnEditorActionListener { v, _, _ -> commit(v); v.clearFocus(); true }
            setOnFocusChangeListener { v, has -> if (!has) commit(v as android.widget.TextView) }
        }))
        body.addView(ggRow("Edit buttons", "Open the keymap editor for this config", ggChevron()) {
            if (p.id != profile.id) setProfile(p)
            hidePanel(); openEditor()
        })
        body.addView(ggRow("Duplicate", trailing = ggChevron()) {
            val copy = Store.importJson(this, p.toJson().toString()); copy.name = p.name + " copy"; Store.save(this, copy)
            showConfigOptions(copy.id)
        })
        body.addView(ggRow("Copy share code", "Paste it in Configs → Import on another device", ggChevron()) {
            runCatching {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("config", p.toJson().toString()))
            }
            Toast.makeText(this, "Share code copied", Toast.LENGTH_SHORT).show()
        })
        if (Store.profiles(this).size > 1) body.addView(ggRow("Delete", trailing = ggChevron()) {
            Store.delete(this, p.id)
            if (p.id == profile.id) setProfile(Store.active(this))
            showConfigs()
        })
        val (w, h) = bigPanelSize()
        showPanel(root, w, h, Gravity.CENTER, dim = true)
    }

    // ---- recoil pill → adjust value ----

    /** "Adjust Value" panel opened from the recoil pill. */
    private fun showAdjust() {
        val (root, body) = indigoPanel("Adjust Value", ::hidePanel)
        body.setBackgroundColor(0xF0262A3A.toInt())
        body.addView(ggSwitchRow("Recoil Control", if (profile.recoilEnabled) "On" else "Off", on = profile.recoilEnabled) {
            profile.recoilEnabled = it; saveProfileLive()
        })
        body.addView(adjustValueBody(profile, ::saveProfileLive,
            rename = { name -> if (name.isNotEmpty()) { profile.activeWeaponObj().name = name; saveProfileLive() }; showAdjust() },
            openDynamic = ::showDynamic,
            refresh = ::showAdjust))
        body.addView(ggRow("All recoil settings", "Keys, slots, aiming, stance, curves", ggChevron()) {
            val page = settings.pages().first { it.first == "General Recoil Control Settings" }
            showSettingsPage(page.first, page.second, ::showAdjust)
        })
        showPanel(root, dp(400f), (realScreenSize().y * 0.82f).toInt(), Gravity.BOTTOM or Gravity.START, dim = false)
    }

    private fun showDynamic() {
        val (root, body) = indigoPanel("Dynamic: ${profile.activeWeaponObj().name}", ::hidePanel, onBack = ::showAdjust)
        body.addView(dynamicBody(profile.activeWeaponObj(), ::saveProfileLive, ::showDynamic))
        val (w, h) = bigPanelSize()
        showPanel(root, w, h, Gravity.CENTER, dim = true)
    }

    private fun setProfile(p: Profile) {
        Store.setActive(this, p.id)
        profile = p
        ServerConnection.setProfile(p)
        hudView?.profile = p
        updatePill()
    }

    // ---- editor ----

    private fun openEditor() {
        if (editor != null) return
        hidePanel()
        val working = Profile.fromJson(profile.toJson())
        val v = EditorView(this, working) { save ->
            if (save) {
                Store.save(this, working)
                setProfile(working)
            }
            closeEditor()
        }
        val p = params(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        wm.addView(v, p)
        editor = v
        applyVisibility()
        updatePause()
    }

    private fun closeEditor() {
        editor?.let { runCatching { wm.removeView(it) } }
        editor = null
        applyVisibility()
        updatePause()
    }

    // ---- in game / out of game ----

    private fun applyVisibility() {
        fun vis(on: Boolean) = if (on) View.VISIBLE else View.GONE
        top?.visibility = vis(inGame && !logoHidden && editor == null)
        // Pointer, labels and crosshair only draw while mapping is on; hiding the window the rest
        // of the time means it can never get in the way of real touches.
        passThrough?.visibility = vis(inGame && editor == null && ServerConnection.state.enabled)
        monitor?.visibility = vis(inGame)
    }

    /** Mapping is paused while editing or while another app is in front. */
    private fun updatePause() = ServerConnection.setPaused(editor != null || panel != null || !inGame)

    private fun setInGame(on: Boolean) {
        if (inGame == on) return
        inGame = on
        if (!on) { hideBar(); hidePanel() }
        applyVisibility()
        updatePause()
    }

    /** Remembers the app in front when mapping is switched on: that's the game. */
    private fun captureGame() {
        io.execute {
            val pkg = ServerConnection.foregroundPackage().orEmpty()
            main.post {
                if (pkg.isNotEmpty() && pkg != packageName) { gamePkg = pkg; lastForeground = pkg; setInGame(true) }
            }
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- games: launch, auto-switch profile, display override ----

    private fun applyGame(pkg: String) {
        val game = AppSettings.get(this).games.firstOrNull { it.pkg == pkg } ?: return
        game.profileId?.let { id ->
            if (id != profile.id) Store.profiles(this).firstOrNull { it.id == id }?.let {
                setProfile(it)
                Toast.makeText(this, "${game.label}: ${it.name}", Toast.LENGTH_SHORT).show()
            }
        }
        if (game.width > 0 || game.density > 0) {
            ServerConnection.setDisplaySize(game.width, game.height, game.density)
            displayFor = pkg
        }
    }

    private fun launchGame(pkg: String) {
        applyGame(pkg)
        gamePkg = pkg
        lastForeground = pkg
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun watchForeground() {
        if (!running) return
        io.execute {
            val pkg = ServerConnection.foregroundPackage().orEmpty()
            main.post { onForeground(pkg) }
        }
        main.postDelayed(::watchForeground, 1000)
    }

    private fun onForeground(pkg: String) {
        if (!running || pkg.isEmpty()) return
        val st = AppSettings.get(this)
        val listed = st.games.any { it.pkg == pkg }
        if (pkg != lastForeground) {
            lastForeground = pkg
            if (listed) {
                gamePkg = pkg
                if (st.autoSwitch) applyGame(pkg)
            } else if (displayFor != null) {
                // Left the game: undo its display override.
                ServerConnection.setDisplaySize(0, 0, 0); displayFor = null
            }
        }
        // Never on the home screen or in this app. Elsewhere: in the known game, or anywhere until
        // a game is known (mapping never started, nothing in "My games").
        val game = gamePkg
        val notAGame = pkg in homePackages || pkg == packageName
        setInGame(!st.autoHide || (!notAGame && (game == null || pkg == game || listed)))
    }

    /** Launcher apps (the home screen). */
    private val homePackages: Set<String> by lazy {
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).map { it.activityInfo.packageName }.toSet()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Overlay", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("MapMouse Pro is running")
            .setContentText("Tap the logo at the top of your game for controls")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    companion object {
        private const val CHANNEL = "overlay"
        const val ACTION_STOP = "stop"
        const val ACTION_RELOAD = "reload"
        const val ACTION_SETTINGS = "settings"
        const val ACTION_OPTIONS = "options"
        const val ACTION_LAUNCH = "launch"
        const val EXTRA_PKG = "pkg"
        /** Must stay below Android's 0.8 limit for touches passing through overlays. */
        private const val PASS_THROUGH_ALPHA = 0.75f
        @Volatile var running = false; private set

        fun start(ctx: Context, action: String? = null, pkg: String? = null) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java).setAction(action).putExtra(EXTRA_PKG, pkg))
        }
    }
}
