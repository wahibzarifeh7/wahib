package com.mapmouse.pro.server

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Server-side logging: logcat (tag MapMouseSrv), stderr, and a small in-memory buffer
 * the app can show (the server runs as another user, so the app can't read its logcat).
 */
object Log {
    private const val TAG = "MapMouseSrv"
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun i(msg: String) { android.util.Log.i(TAG, msg); System.err.println("I $msg"); keep("I", msg) }
    fun e(msg: String) { android.util.Log.e(TAG, msg); System.err.println("E $msg"); keep("E", msg) }

    fun e(msg: String, t: Throwable) = e("$msg: ${android.util.Log.getStackTraceString(t).take(1500)}")

    @Synchronized private fun keep(level: String, msg: String) {
        lines.addLast("${time.format(Date())} $level $msg")
        while (lines.size > 200) lines.removeFirst()
    }

    @Synchronized fun dump(): String = lines.joinToString("\n")
}
