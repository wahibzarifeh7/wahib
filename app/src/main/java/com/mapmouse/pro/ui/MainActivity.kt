package com.mapmouse.pro.ui

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mapmouse.pro.AppSettings
import com.mapmouse.pro.BuildConfig
import com.mapmouse.pro.Game
import com.mapmouse.pro.ServerConnection
import com.mapmouse.pro.Store
import com.mapmouse.pro.service.OverlayService
import rikka.shizuku.Shizuku

/**
 * Home: a title bar, the current tab's content and a bottom navigation with three tabs —
 * Main Page (my games), Configs (keymap profiles) and More (settings list).
 */
class MainActivity : PageActivity(), ServerConnection.Listener {

    private enum class Tab(val label: String, val icon: Icon) { MAIN("Main Page", Icon.GAMEPAD), CONFIGS("Configs", Icon.CONFIGS), MORE("More", Icon.GRID) }

    private var tab = Tab.MAIN
    private lateinit var nav: LinearLayout
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }
    private var lastState: ServerConnection.State? = null
    private var lastError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PageActivity's layout plus a bottom navigation bar.
        val content = findViewById<View>(android.R.id.content) as FrameLayout
        val column = content.getChildAt(0) as LinearLayout
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Pal.BAR)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        column.addView(View(this).apply { setBackgroundColor(Pal.LINE) }, LinearLayout.LayoutParams(-1, dp(1f)))
        column.addView(nav, LinearLayout.LayoutParams(-1, dp(68f)))
        tab = savedInstanceState?.getString("tab")?.let { runCatching { Tab.valueOf(it) }.getOrNull() } ?: Tab.MAIN

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        ServerConnection.addListener(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.name)
    }

    override fun onResume() {
        super.onResume()
        ServerConnection.connect(this)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        ServerConnection.removeListener(this)
        super.onDestroy()
    }

    override fun onState(state: ServerConnection.State) {
        val error = ServerConnection.startError
        if (state != lastState || error != lastError) { lastState = state; lastError = error; refresh() }
        // Bring the in-game overlay up by itself once the server is ready (unless closed by hand).
        if (state.connected && !OverlayService.running && !overlayAutoStarted && !userHidOverlay &&
            Settings.canDrawOverlays(this)) {
            overlayAutoStarted = true
            OverlayService.start(this, OverlayService.ACTION_RELOAD)
        }
    }

    override fun build() {
        title("MapMouse Pro", back = false)
        buildNav()
        when (tab) {
            Tab.MAIN -> mainPage()
            Tab.CONFIGS -> configsPage()
            Tab.MORE -> morePage()
        }
    }

    private fun buildNav() {
        if (!::nav.isInitialized) return
        nav.removeAllViews()
        for (t in Tab.entries) {
            val on = t == tab
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                if (on) background = roundBg(Pal.ACCENT, dp(26f).toFloat())
                addView(IconView(context, t.icon, if (on) Color.WHITE else Pal.ACCENT, 26f))
                if (on) addView(TextView(context).apply {
                    text = t.label; textSize = 17f; setTextColor(Color.WHITE); setPadding(dp(8f), 0, 0, 0)
                })
                setOnClickListener { tab = t; refresh() }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, -1, if (on) 1.6f else 1f).apply { marginStart = dp(4f); marginEnd = dp(4f) })
        }
    }

    // ---- Main Page ----

    private fun mainPage() {
        val st = ServerConnection.state
        if (!st.connected) {
            // Not activated banner, like a call to action.
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xFFFFF4E5.toInt())
                setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
                addView(label(ServerConnection.startError?.let { "Activation failed. Tap Activate for details." }
                    ?: "Not activated. Unable to play games using mouse, keyboard or gamepad.", 14f, 0xFF8A5A00.toInt()),
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(primaryButton("Activate") { openPage(SettingsActivity.PAGE_ACTIVATION) })
            })
        } else if (!Settings.canDrawOverlays(this)) {
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xFFFFF4E5.toInt())
                setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
                addView(label("Required permissions needed: display over other apps.", 14f, 0xFF8A5A00.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
                addView(primaryButton("Grant") { openPage(SettingsActivity.PAGE_PERMISSIONS) })
            })
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18f), dp(20f), dp(18f), dp(8f))
            addView(label("My Games:", 22f, Pal.INK).apply { paint.isFakeBoldText = true })
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.ACCENT) }
                addView(TextView(context).apply { text = "+"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(-1, -1))
                setOnClickListener { addGame() }
            }, LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { marginStart = dp(4f) })
        })
        val app = AppSettings.get(this)
        if (app.games.isEmpty()) note(root, "    Tap + to add the games you play. Launching from here switches to the right config.")
        for (g in app.games) root.addView(gameRow(g))
        root.addView(View(this), LinearLayout.LayoutParams(-1, dp(12f)))
        val active = Store.active(this)
        root.addView(ggRow("Active config", "${active.name} · ${active.controls.size} buttons", ggValue("Change")) { tab = Tab.CONFIGS; refresh() })
        root.addView(ggSwitchRow("Show in-game controls",
            "The logo button appears at the top of your game", on = OverlayService.running) { on ->
            if (on) {
                if (!Settings.canDrawOverlays(this)) { toast("Allow display over other apps first"); openPage(SettingsActivity.PAGE_PERMISSIONS) }
                else { userHidOverlay = false; OverlayService.start(this, OverlayService.ACTION_RELOAD); toast("The logo button appears when you open a game") }
            } else { userHidOverlay = true; OverlayService.start(this, OverlayService.ACTION_STOP) }
        })
    }

    private fun gameRow(g: Game): View {
        val icon = runCatching { packageManager.getApplicationIcon(g.pkg) }.getOrNull()
        val version = runCatching { packageManager.getPackageInfo(g.pkg, 0).versionName }.getOrNull().orEmpty()
        val profiles = Store.profiles(this)
        val prof = profiles.firstOrNull { it.id == g.profileId }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
            addView(ImageView(context).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(54f), dp(54f)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16f), 0, 0, 0)
                addView(label(g.label, 18f, Pal.INK))
                addView(label(listOf(version, prof?.let { "Config: ${it.name}" }).filterNotNull().filter { it.isNotEmpty() }
                    .joinToString("  ·  "), 13f, Pal.SUB))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(primaryButton("Launch") { launch(g) })
            setOnClickListener { gameOptions(g) }
            setOnLongClickListener { gameOptions(g); true }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(View(context).apply { setBackgroundColor(Pal.LINE) }, LinearLayout.LayoutParams(-1, dp(1f)).apply {
                marginStart = dp(20f); marginEnd = dp(20f) })
        }
    }

    private fun gameOptions(g: Game) {
        val app = AppSettings.get(this)
        val profiles = Store.profiles(this)
        AlertDialog.Builder(this).setTitle(g.label).setItems(arrayOf("Choose config", "Screen size", "Remove from My Games")) { _, i ->
            when (i) {
                0 -> {
                    val names = listOf("(current)") + profiles.map { it.name }
                    AlertDialog.Builder(this).setTitle("Config for ${g.label}").setItems(names.toTypedArray()) { _, j ->
                        g.profileId = if (j == 0) null else profiles[j - 1].id; app.save(this); refresh()
                    }.show()
                }
                1 -> editScreen(g)
                2 -> { app.games.remove(g); app.save(this); refresh() }
            }
        }.show()
    }

    // ---- Configs ----

    private fun configsPage() {
        root.addView(ggHeader("My Keymaps").apply { setPadding(dp(16f), dp(10f), dp(16f), dp(10f)) })
        val active = Store.active(this)
        for (p in Store.profiles(this)) {
            val isActive = p.id == active.id
            root.addView(ggRow((if (isActive) "●  " else "○  ") + p.name,
                "Component count: ${p.controls.size}" + if (isActive) "  ·  in use" else "",
                ggValue(if (isActive) "Options" else "Use")) {
                if (!isActive) { Store.setActive(this, p.id); reloadOverlay(); refresh() } else configOptions(p.id)
            })
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(16f), 0, 0) }
        box.addView(primaryButton("Create") {
            prompt("New config", "Game ${Store.profiles(this).size + 1}") { if (it.isNotEmpty()) { Store.setActive(this, Store.create(this, it).id); reloadOverlay(); refresh() } }
        })
        box.addView(primaryButton("Import") {
            AlertDialog.Builder(this).setItems(arrayOf("Paste share code", "From file")) { _, i ->
                if (i == 0) importText(getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString())
                else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT)
            }.show()
        })
        root.addView(box)
        note(root, "    Edit a config from the in-game logo button → Editor, so you can place buttons over the game.")
    }

    private fun configOptions(id: String) {
        val p = Store.profiles(this).firstOrNull { it.id == id } ?: return
        AlertDialog.Builder(this).setTitle(p.name).setItems(arrayOf("Rename", "Duplicate", "Share (copy code)", "Delete")) { _, i ->
            when (i) {
                0 -> prompt("Rename config", p.name) { if (it.isNotEmpty()) { p.name = it; Store.save(this, p); reloadOverlay(); refresh() } }
                1 -> { val copy = Store.importJson(this, p.toJson().toString()); copy.name = p.name + " copy"; Store.save(this, copy); refresh() }
                2 -> {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("config", p.toJson().toString()))
                    toast("Share code copied. Paste it in Configs → Import on the other device")
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, p.toJson().toString()), "Share config"))
                }
                3 -> confirm("Delete ${p.name}?") { Store.delete(this, p.id); refresh() }
            }
        }.show()
    }

    // ---- More ----

    private fun morePage() {
        val running = ServerConnection.shizukuRunning()
        val st = ServerConnection.state
        // App mark.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(18f), 0, dp(10f))
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.ACCENT) }
                addView(IconView(context, Icon.MOUSE, Color.WHITE, 40f), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(76f), dp(76f)))
            addView(label("MAPMOUSE PRO", 18f, Pal.SUB).apply { setPadding(0, dp(8f), 0, 0); letterSpacing = 0.12f })
        })
        root.addView(ggRow("Activation Mode", trailing = ggValue(if (running) "Shizuku" else "Shizuku (not running)")) {
            openPage(SettingsActivity.PAGE_ACTIVATION) })
        root.addView(ggRow("Activation Assistant",
            if (st.connected) "Input server running (${ServerConnection.mode})" else "Not activated", ggValue("")) {
            openPage(SettingsActivity.PAGE_ACTIVATION) })
        root.addView(ggRow("Customize Mouse Style", trailing = ggValue("")) { openPage(SettingsActivity.PAGE_MOUSE) })
        root.addView(ggRow("Permissions Management", trailing = ggValue("")) { openPage(SettingsActivity.PAGE_PERMISSIONS) })
        val app = AppSettings.get(this)
        root.addView(ggSwitchRow("Touch Compatible mode", "Finger touches will work with mouse and keyboard at same time",
            on = app.touchCompat) { app.touchCompat = it; saveOptions() })
        root.addView(ggSwitchRow("Lift Aim Finger When Mouse Stops", "Lets you tap the screen while mapping is on",
            on = app.touchFriendly) { app.touchFriendly = it; saveOptions() })
        root.addView(ggSwitchRow("Pause & Hide Outside the Game", "Mapping pauses and the logo hides when you leave the game",
            on = app.autoHide) { app.autoHide = it; saveOptions() })
        root.addView(ggSwitchRow("Auto-switch Config", "Use each game's own config when it opens",
            on = app.autoSwitch) { app.autoSwitch = it; app.save(this) })
        root.addView(ggRow("Recoil Control", "12 slots on F1–F12, curves, stance, aim-only", ggValue("")) {
            startActivity(Intent(this, RecoilActivity::class.java)) })
        root.addView(ggRow("HUD & Crosshair", trailing = ggValue("")) { openPage(SettingsActivity.PAGE_HUD) })
        root.addView(ggRow("Device Info Monitor", trailing = ggValue("")) { openPage(SettingsActivity.PAGE_MONITOR) })
        root.addView(ggRow("Gamepad & Advanced", trailing = ggValue("")) { openPage(SettingsActivity.PAGE_ADVANCED) })
        root.addView(ggRow("How to Use", trailing = ggValue("")) { showHelp() })
        root.addView(ggRow("App Version", trailing = ggValue(BuildConfig.VERSION_NAME, chevron = false)))
        root.addView(ggRow("Reset All Settings", trailing = ggValue("")) {
            confirm("Reset all settings to default? Configs are kept.") {
                AppSettings().also { it.games = AppSettings.get(this).games }.save(this); saveOptions(); refresh()
            }
        })
        // Device info footer.
        val mem = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val size = realScreenSize()
        root.addView(label("Model: ${Build.MANUFACTURER} ${Build.MODEL} | System: Android ${Build.VERSION.RELEASE} | " +
            "Resolution: ${maxOf(size.x, size.y)}x${minOf(size.x, size.y)}\nTotal Memory(MB): ${mem.totalMem / 1048576} | " +
            "Available Memory(MB): ${mem.availMem / 1048576}", 13f, Pal.SUB).apply {
            gravity = Gravity.CENTER; setPadding(dp(16f), dp(12f), dp(16f), dp(8f))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("How to use").setMessage("""
            1. More → Activation Assistant: start Shizuku and activate.
            2. Allow "display over other apps" (Permissions Management).
            3. Main Page: add your game with +, then Launch.
            4. In the game, tap the logo at the top → Editor. Add buttons, drag them over the game's buttons, double-tap one to set its key and options, then Save.
            5. Logo → Start (or press Pause). Your keyboard, mouse or gamepad now plays the game.

            • ` switches between aiming and a free mouse pointer.
            • F1–F12 switch recoil slots; tap the slot pill next to the logo to adjust it. Ctrl + mouse wheel changes the value.
            • Scroll Lock shows/hides key labels.
            • Leaving the game pauses everything so you can use your phone normally.
        """.trimIndent()).setPositiveButton("OK", null).show()
    }

    // ---- actions ----

    private fun openPage(page: String) = startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, page))

    private fun reloadOverlay() {
        if (OverlayService.running) OverlayService.start(this, OverlayService.ACTION_RELOAD)
        else if (ServerConnection.state.connected) ServerConnection.setProfile(Store.active(this))
    }

    private fun launch(g: Game) {
        if (!Settings.canDrawOverlays(this)) { toast("Allow display over other apps first"); openPage(SettingsActivity.PAGE_PERMISSIONS); return }
        userHidOverlay = false
        OverlayService.start(this, OverlayService.ACTION_LAUNCH, g.pkg)
    }

    private fun addGame() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        AlertDialog.Builder(this).setTitle("Add Game").setItems(apps.map { it.second }.toTypedArray()) { _, i ->
            val (pkg, label) = apps[i]
            val s = AppSettings.get(this)
            if (s.games.none { it.pkg == pkg }) { s.games += Game(pkg, label, Store.active(this).id); s.save(this) }
            refresh()
        }.show()
    }

    private fun editScreen(g: Game) {
        val real = realScreenSize()
        val names = arrayOf("Default (no change)", "90% resolution", "75% resolution", "Custom…")
        AlertDialog.Builder(this).setTitle("Screen size while playing ${g.label}").setItems(names) { _, i ->
            val app = AppSettings.get(this)
            // `wm size` takes portrait-style WxH (width < height).
            val w = minOf(real.x, real.y); val h = maxOf(real.x, real.y)
            when (i) {
                0 -> { g.width = 0; g.height = 0; g.density = 0 }
                1 -> { g.width = w * 9 / 10; g.height = h * 9 / 10 }
                2 -> { g.width = w * 3 / 4; g.height = h * 3 / 4 }
                3 -> {
                    prompt("Width x Height (e.g. 1080x2400)", if (g.width > 0) "${g.width}x${g.height}" else "${w}x$h") { v ->
                        val parts = v.lowercase().split("x").mapNotNull { it.trim().toIntOrNull() }
                        if (parts.size == 2 && parts.all { it in 500..5000 }) { g.width = minOf(parts[0], parts[1]); g.height = maxOf(parts[0], parts[1]) }
                        else toast("Enter a size between 500 and 5000, like 1080x2400")
                        prompt("Density (200–800, empty = default)", if (g.density > 0) g.density.toString() else "", numeric = true) { d ->
                            g.density = d.toIntOrNull()?.takeIf { it in 200..800 } ?: 0
                            app.save(this); refresh()
                        }
                    }
                    return@setItems
                }
            }
            app.save(this); refresh()
        }.show()
    }

    private fun importText(text: String?) {
        if (text.isNullOrBlank()) return toast("Nothing to import")
        runCatching { Store.importJson(this, text.trim()) }
            .onSuccess { toast("Imported ${it.name}"); refresh() }
            .onFailure { toast("Not a valid config") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            importText(runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull())
        }
    }

    companion object {
        private const val REQ_IMPORT = 2
        /** Per app process: start the overlay automatically only once, and never after it was switched off. */
        private var overlayAutoStarted = false
        var userHidOverlay = false
    }
}
