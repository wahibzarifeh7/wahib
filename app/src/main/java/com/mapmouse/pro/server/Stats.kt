package com.mapmouse.pro.server

import org.json.JSONObject
import java.io.File

/**
 * Device performance numbers that need shell rights: game FPS (SurfaceFlinger frame
 * counter), CPU usage (/proc/stat) and CPU temperature (thermal zones).
 */
class Stats {
    private var lastFrames = -1L
    private var lastFrameTime = 0L
    private var lastIdle = 0L
    private var lastTotal = 0L
    private var cpuZone: File? = null
    private var zoneSearched = false

    @Synchronized
    fun read(): JSONObject {
        val o = JSONObject()
        fps()?.let { o.put("fps", it.toDouble()) }
        cpu()?.let { o.put("cpu", it.toDouble()) }
        temp()?.let { o.put("temp", it.toDouble()) }
        return o
    }

    /** `service call SurfaceFlinger 1013` returns the number of frames composited so far. */
    private fun fps(): Float? {
        val out = Shell.run("service call SurfaceFlinger 1013") ?: return null
        val hex = Regex("""Parcel\(([0-9a-fA-F]+)""").find(out)?.groupValues?.get(1) ?: return null
        val frames = hex.toLongOrNull(16) ?: return null
        val now = System.nanoTime()
        val result = if (lastFrames >= 0 && frames >= lastFrames && now > lastFrameTime)
            (frames - lastFrames) * 1e9f / (now - lastFrameTime) else null
        lastFrames = frames; lastFrameTime = now
        return result
    }

    private fun cpu(): Float? {
        val line = runCatching { File("/proc/stat").useLines { it.first() } }.getOrNull() ?: return null
        val v = line.split(Regex("""\s+""")).drop(1).mapNotNull { it.toLongOrNull() }
        if (v.size < 4) return null
        val idle = v[3] + (v.getOrNull(4) ?: 0)
        val total = v.sum()
        val result = if (lastTotal > 0 && total > lastTotal)
            100f * (1f - (idle - lastIdle).toFloat() / (total - lastTotal)) else null
        lastIdle = idle; lastTotal = total
        return result
    }

    private fun temp(): Float? {
        if (!zoneSearched) {
            zoneSearched = true
            val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty()
            cpuZone = zones.firstOrNull { z ->
                val type = runCatching { File(z, "type").readText().lowercase() }.getOrDefault("")
                ("cpu" in type || "soc" in type || "tsens" in type) && runCatching { File(z, "temp").readText() }.isSuccess
            } ?: zones.firstOrNull { runCatching { File(it, "temp").readText() }.isSuccess }
        }
        val raw = runCatching { cpuZone?.let { File(it, "temp").readText().trim().toFloat() } }.getOrNull() ?: return null
        return if (raw > 1000) raw / 1000f else raw
    }
}
