package com.mapmouse.pro.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.widget.TextView
import com.mapmouse.pro.AppSettings
import com.mapmouse.pro.ServerConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Small in-game panel with FPS, CPU, temperature, battery, network speed, ping, clock and
 * play time. Values that need shell rights (FPS, CPU, temperature) come from the input server.
 */
class MonitorView(context: Context) : TextView(context) {
    private val io = Executors.newSingleThreadExecutor()
    private var lastRx = TrafficStats.getTotalRxBytes()
    private var lastTx = TrafficStats.getTotalTxBytes()
    private var lastNetTime = System.currentTimeMillis()
    private var ping: Int? = null
    private var running = false
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** When mapping started, for the play-time counter. */
    var playStart = 0L

    /** FPS/CPU/temperature history for the graph viewer (last 10 minutes). */
    companion object {
        val history = ArrayDeque<Triple<Float, Float, Float>>()
    }

    init {
        setTextColor(Color.WHITE)
        textSize = 11f
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
        setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        background = roundBg(0x88000000.toInt(), dp(6f).toFloat())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        refresh()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        if (!running) return
        val st = AppSettings.get(context)
        io.execute {
            val stats = ServerConnection.stats()
            if (st.monPing) ping = pingGateway()
            post { render(st, stats) }
        }
        postDelayed({ refresh() }, st.monRefreshMs.toLong())
    }

    private fun render(st: AppSettings, stats: org.json.JSONObject?) {
        val parts = mutableListOf<String>()
        val fps = stats?.optDouble("fps", Double.NaN) ?: Double.NaN
        val cpu = stats?.optDouble("cpu", Double.NaN) ?: Double.NaN
        val temp = stats?.optDouble("temp", Double.NaN) ?: Double.NaN
        if (st.monFps) parts += if (fps.isNaN()) "FPS --" else "FPS %.0f".format(fps)
        if (st.monCpu) parts += if (cpu.isNaN()) "CPU --" else "CPU %.0f%%".format(cpu)
        if (st.monTemp) parts += if (temp.isNaN()) "-- °C" else "%.0f°C".format(temp)
        if (st.monBattery) parts += "🔋${battery()}%"
        if (st.monNet) {
            val now = System.currentTimeMillis()
            val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes()
            val secs = ((now - lastNetTime) / 1000f).coerceAtLeast(0.1f)
            parts += "↓${speed((rx - lastRx) / secs)} ↑${speed((tx - lastTx) / secs)}"
            lastRx = rx; lastTx = tx; lastNetTime = now
        }
        if (st.monPing) parts += ping?.let { "Ping ${it}ms" } ?: "Ping --"
        if (st.monTime) parts += clock.format(Date())
        if (st.monPlayTime && playStart > 0) {
            val m = (System.currentTimeMillis() - playStart) / 60000
            parts += "Play ${m / 60}h${"%02d".format(m % 60)}m"
        }
        text = parts.joinToString("  ")
        if (!fps.isNaN() || !cpu.isNaN()) {
            history.addLast(Triple(fps.toFloat(), cpu.toFloat(), temp.toFloat()))
            while (history.size > 600) history.removeFirst()
        }
    }

    private fun battery(): Int {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0) -1 else level * 100 / scale
    }

    private fun speed(bps: Float) = when {
        bps > 1_000_000 -> "%.1fM".format(bps / 1_000_000)
        bps > 1000 -> "%.0fK".format(bps / 1000)
        else -> "%.0fB".format(bps)
    }

    /** Round trip to the Wi-Fi router. */
    private fun pingGateway(): Int? = runCatching {
        @Suppress("DEPRECATION")
        val gw = context.applicationContext.getSystemService(WifiManager::class.java).dhcpInfo.gateway
        if (gw == 0) return null
        val ip = "${gw and 0xff}.${gw shr 8 and 0xff}.${gw shr 16 and 0xff}.${gw shr 24 and 0xff}"
        val p = ProcessBuilder("ping", "-c", "1", "-W", "1", ip).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        Regex("time=([\\d.]+)").find(out)?.groupValues?.get(1)?.toFloat()?.toInt()
    }.getOrNull()
}
