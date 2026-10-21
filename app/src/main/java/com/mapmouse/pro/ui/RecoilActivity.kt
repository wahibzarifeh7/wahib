package com.mapmouse.pro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import com.mapmouse.pro.Store
import com.mapmouse.pro.model.CurvePoint
import com.mapmouse.pro.model.Profile
import com.mapmouse.pro.model.Weapon
import org.json.JSONArray

/**
 * Recoil control for the active profile: weapons (each with vertical/horizontal values, an
 * optional curve over time and a select key), stance multipliers, aim-only, hotkeys.
 */
class RecoilActivity : PageActivity() {
    private lateinit var p: Profile
    /** Weapon whose curve editor is expanded. */
    private var curveOpen = -1

    override fun build() {
        p = Store.active(this)
        val save = { saveSettings(p) }
        title("Recoil control")
        root.addView(label("Profile: ${p.name}", 14f, Pal.SUB).apply { setPadding(dp(16f), dp(8f), dp(16f), 0) })

        val basic = section("Basic", "Values are screen-heights per second. Adjust them in game with Ctrl + mouse wheel or the hotkeys below.")
        basic.addView(scroll(
            toggleChip("Enable recoil control", { p.recoilEnabled }) { p.recoilEnabled = it; save() },
            keyChip("Fire key", { p.recoilKey }) { p.recoilKey = it; save() },
        ))
        basic.addView(stepSlider("Start delay", 0f, 500f, 10f, p.recoilDelay.toFloat(), { "${it.toInt()} ms" }) { p.recoilDelay = it.toInt(); save() })
        basic.addView(stepSlider("Global strength", 0.2f, 3f, 0.05f, p.recoilGlobal, { "${(it * 100).toInt()}%" }) { p.recoilGlobal = it; save() })
        note(basic, "Touch controls can also trigger recoil (their \"Trigger recoil\" option), and macros have Recoil start/stop steps.")

        val aim = section("Aiming")
        aim.addView(scroll(
            toggleChip("Stop recoil control when not aiming", { p.recoilOnlyAiming }) { p.recoilOnlyAiming = it; save() },
        ))
        aim.addView(scroll(
            keyChip("Aim key", { p.aimKey }) { p.aimKey = it; save() },
            cycleChip("Aim mode", listOf("Hold", "Click (toggle)"), { if (p.aimToggle) 1 else 0 }) { p.aimToggle = it == 1; save() },
        ))
        note(aim, "Match the game's scope setting (hold or tap to aim), otherwise the aim state gets out of sync.")

        val horiz = section("Horizontal recoil control")
        horiz.addView(scroll(
            keyChip("On/off key", { p.horizontalKey }) { p.horizontalKey = it; save() },
            cycleChip("Enable type", listOf("Press (while held)", "Switch"), { if (p.horizontalToggle) 1 else 0 }) { p.horizontalToggle = it == 1; save() },
        ))
        note(horiz, "With no key set, horizontal control is always on.")

        val stance = section("Stance", "Pressing crouch/prone toggles the stance; recoil is multiplied by its factor.")
        stance.addView(scroll(
            keyChip("Crouch key", { p.crouchKey }) { p.crouchKey = it; save() },
            keyChip("Prone key", { p.proneKey }) { p.proneKey = it; save() },
        ))
        stance.addView(stepSlider("Crouch factor", 0.1f, 1.5f, 0.05f, p.crouchCoef, { "${(it * 100).toInt()}%" }) { p.crouchCoef = it; save() })
        stance.addView(stepSlider("Prone factor", 0.1f, 1.5f, 0.05f, p.proneCoef, { "${(it * 100).toInt()}%" }) { p.proneCoef = it; save() })

        val keys = section("Adjust hotkeys")
        keys.addView(scroll(
            keyChip("Value +", { p.rcUpKey }) { p.rcUpKey = it; save() },
            keyChip("Value −", { p.rcDownKey }) { p.rcDownKey = it; save() },
            keyChip("Show/hide panel", { p.rcPanelKey }) { p.rcPanelKey = it; save() },
        ))
        keys.addView(stepSlider("Step", 0.001f, 0.02f, 0.001f, p.rcStep, { "%.3f".format(it) }) { p.rcStep = it; save() })

        val ws = section("Weapons", "Each weapon has its own values. Give it the key you use to switch to it in game (e.g. 1 / 2) and it's selected automatically.")
        p.weapons.forEachIndexed { i, w -> weaponBlock(ws, i, w, save) }
        ws.addView(scroll(
            chip("+ Add weapon") { p.weapons += Weapon("Weapon ${p.weapons.size + 1}", p.recoilStrength, p.recoilStrengthX); save(); refresh() },
            chip("Copy all (share)") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("weapons",
                    JSONArray().apply { p.weapons.forEach { put(it.toJson()) } }.toString()))
                toast("Weapons copied")
            },
            chip("Paste") {
                val txt = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                runCatching {
                    val arr = JSONArray(txt!!)
                    val list = (0 until arr.length()).map { Weapon.fromJson(arr.getJSONObject(it)) }
                    require(list.isNotEmpty())
                    p.weapons = list.toMutableList(); p.activeWeapon = 0
                }.onSuccess { save(); refresh() }.onFailure { toast("No weapons in clipboard") }
            },
        ))
    }

    private fun weaponBlock(box: android.widget.LinearLayout, i: Int, w: Weapon, save: () -> Unit) {
        val active = i == p.activeWeapon
        box.addView(label((if (active) "●  " else "○  ") + w.name, 15f, if (active) Pal.ACCENT else Pal.INK).apply {
            setPadding(dp(4f), dp(12f), dp(4f), dp(2f))
        })
        box.addView(scroll(
            chip("Use") { p.activeWeapon = i; save(); refresh() },
            chip("Rename") { prompt("Weapon name", w.name) { if (it.isNotEmpty()) { w.name = it; save(); refresh() } } },
            keyChip("Select key", { w.key }) { w.key = it; save() },
            toggleChip("Crazy tap", { w.crazy }) { w.crazy = it; save() },
            chip(if (curveOpen == i) "Hide curve" else "Curve (${w.curve.size})") { curveOpen = if (curveOpen == i) -1 else i; refresh() },
            chip("Delete") {
                if (p.weapons.size == 1) return@chip toast("Keep at least one weapon")
                confirm("Delete ${w.name}?") {
                    p.weapons.removeAt(i); p.activeWeapon = p.activeWeapon.coerceIn(0, p.weapons.size - 1); save(); refresh()
                }
            },
        ))
        box.addView(stepSlider("Vertical ↓", 0f, 0.4f, 0.001f, w.v, { "%.3f".format(it) }) { w.v = it; save() })
        box.addView(stepSlider("Horizontal", -0.2f, 0.2f, 0.001f, w.h, {
            when { it < 0f -> "%.3f ←".format(-it); it > 0f -> "%.3f →".format(it); else -> "off" }
        }) { w.h = it; save() })
        if (curveOpen == i) curveEditor(box, w, save)
    }

    /** Dynamic recoil: strength changes over the spray (e.g. stronger after the first bullets). */
    private fun curveEditor(box: android.widget.LinearLayout, w: Weapon, save: () -> Unit) {
        note(box, "Curve points: strength from a time after firing starts (linear in between). With no points the fixed values above are used.")
        w.curve.sortBy { it.t }
        w.curve.forEachIndexed { j, pt ->
            box.addView(label("Point ${j + 1}", 13f, Pal.INK))
            box.addView(stepSlider("Time", 0f, 5000f, 50f, pt.t.toFloat(), { "%.2f s".format(it / 1000) }) { pt.t = it.toInt(); save() })
            box.addView(stepSlider("Vertical", 0f, 0.4f, 0.001f, pt.v, { "%.3f".format(it) }) { pt.v = it; save() })
            box.addView(stepSlider("Horizontal", -0.2f, 0.2f, 0.001f, pt.h, { "%.3f".format(it) }) { pt.h = it; save() })
            box.addView(chip("Delete point") { w.curve.removeAt(j); save(); refresh() })
        }
        box.addView(scroll(
            chip("+ Point") {
                val last = w.curve.lastOrNull()
                w.curve += CurvePoint((last?.t ?: -500) + 500, last?.v ?: w.v, last?.h ?: w.h); save(); refresh()
            },
            chip("Clear curve") { w.curve.clear(); save(); refresh() },
        ))
    }
}
