package com.mapmouse.pro.server

import java.util.concurrent.TimeUnit

/** Runs shell commands from the server process (which has shell or root rights). */
object Shell {
    fun run(cmd: String, timeoutMs: Long = 3000): String? = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }
        reader.start()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.destroy()
        reader.join(500)
        out.toString()
    } catch (e: Exception) {
        Log.e("shell '$cmd': $e")
        null
    }
}
