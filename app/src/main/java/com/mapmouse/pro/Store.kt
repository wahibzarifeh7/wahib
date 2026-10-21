package com.mapmouse.pro

import android.content.Context
import com.mapmouse.pro.model.Profile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Profiles live as JSON files in app storage; small settings in SharedPreferences. */
object Store {
    private fun dir(ctx: Context) = File(ctx.filesDir, "profiles").apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun profiles(ctx: Context): List<Profile> =
        dir(ctx).listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { Profile.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedBy { it.name.lowercase() }

    fun save(ctx: Context, p: Profile) = File(dir(ctx), "${p.id}.json").writeText(p.toJson().toString(2))

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
        if (activeId(ctx) == id) prefs(ctx).edit().remove("active").apply()
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun create(ctx: Context, name: String): Profile = Profile.default(newId(), name).also { save(ctx, it) }

    /** Imports a shared profile under a fresh id so it never overwrites an existing one. */
    fun importJson(ctx: Context, json: String): Profile {
        val p = Profile.fromJson(JSONObject(json))
        p.id = newId()
        save(ctx, p)
        return p
    }

    fun activeId(ctx: Context): String? = prefs(ctx).getString("active", null)

    fun setActive(ctx: Context, id: String) = prefs(ctx).edit().putString("active", id).apply()

    fun active(ctx: Context): Profile {
        val all = profiles(ctx)
        return all.firstOrNull { it.id == activeId(ctx) }
            ?: all.firstOrNull()?.also { setActive(ctx, it.id) }
            ?: create(ctx, "Default").also { setActive(ctx, it.id) }
    }
}
