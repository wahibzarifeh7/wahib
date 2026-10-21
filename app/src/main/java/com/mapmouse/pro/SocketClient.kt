package com.mapmouse.pro

import android.os.RemoteException
import com.mapmouse.pro.server.Main
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * [IInputService] backed by the localhost socket of a server started as a plain process
 * (see [Main]). Lets the rest of the app use one interface for both start methods.
 */
class SocketClient(private val token: String, val port: Int) : IInputService.Stub() {
    private var cmd: Socket? = null
    private lateinit var reader: BufferedReader
    private lateinit var writer: BufferedWriter
    private var evt: Socket? = null

    /** How long a reply may take. Short while probing (a foreign server may never answer). */
    var readTimeoutMs = 1500
        set(v) { field = v; runCatching { cmd?.soTimeout = v } }

    /** Connects the command channel; false if no server is listening. */
    fun open(timeoutMs: Int = 300): Boolean = try {
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        s.tcpNoDelay = true
        s.soTimeout = readTimeoutMs
        reader = s.getInputStream().bufferedReader()
        writer = s.getOutputStream().bufferedWriter()
        writer.write(JSONObject().put("token", token).put("role", "cmd").toString()); writer.newLine(); writer.flush()
        cmd = s
        true
    } catch (_: Exception) {
        false
    }

    fun close() {
        runCatching { cmd?.close() }
        runCatching { evt?.close() }
    }

    @Synchronized
    private fun call(m: String, vararg args: Any?): Any? {
        try {
            val a = JSONArray().apply { args.forEach { put(it) } }
            writer.write(JSONObject().put("m", m).put("a", a).toString()); writer.newLine(); writer.flush()
            val line = reader.readLine() ?: throw RemoteException("server closed the connection")
            val o = JSONObject(line)
            if (o.has("err")) throw RemoteException(o.getString("err"))
            return o.opt("r").takeIf { it != JSONObject.NULL }
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException(e.toString())
        }
    }

    override fun destroy() { runCatching { call("destroy") } }
    override fun version(): Int = (call("version") as? Number)?.toInt() ?: -1
    override fun setScreen(width: Int, height: Int, rotation: Int) { call("setScreen", width, height, rotation) }
    override fun setProfile(json: String) { call("setProfile", json) }
    override fun setEnabled(on: Boolean) { call("setEnabled", on) }
    override fun setPaused(on: Boolean) { call("setPaused", on) }
    override fun listDevices(): String? = call("listDevices") as? String
    override fun updateSettings(json: String) { call("updateSettings", json) }
    override fun setOptions(json: String) { call("setOptions", json) }
    override fun setTyping(on: Boolean) { call("setTyping", on) }
    override fun getStats(): String? = call("getStats") as? String
    override fun setDisplaySize(width: Int, height: Int, density: Int) { call("setDisplaySize", width, height, density) }
    override fun foregroundPackage(): String? = call("foregroundPackage") as? String
    override fun log(): String? = call("log") as? String

    /** Opens the event channel and forwards server events to [cb] on a reader thread. */
    override fun setCallback(cb: IServerCallback?) {
        runCatching { evt?.close() }
        if (cb == null) return
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", port), 1000)
        s.tcpNoDelay = true
        val w = s.getOutputStream().bufferedWriter()
        w.write(JSONObject().put("token", token).put("role", "evt").toString()); w.newLine(); w.flush()
        evt = s
        Thread({
            try {
                val r = s.getInputStream().bufferedReader()
                while (true) {
                    val o = JSONObject(r.readLine() ?: break)
                    val a = o.getJSONArray("a")
                    when (o.getString("e")) {
                        "cursor" -> cb.onCursor(a.getInt(0), a.getInt(1), a.getBoolean(2))
                        "state" -> cb.onState(a.getBoolean(0), a.getBoolean(1), a.getBoolean(2), a.getInt(3), a.getInt(4))
                        "hud" -> cb.onHud(a.getString(0))
                        "pointers" -> cb.onPointers(IntArray(a.length()) { a.getInt(it) })
                    }
                }
            } catch (_: Exception) {
            }
            onClosed?.invoke()
        }, "server-events").apply { isDaemon = true }.start()
    }

    /** Called when the event channel drops (server died or was stopped). */
    var onClosed: (() -> Unit)? = null
}
