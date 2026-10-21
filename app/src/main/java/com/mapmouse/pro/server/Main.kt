package com.mapmouse.pro.server

import android.os.Looper
import com.mapmouse.pro.IServerCallback
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

/**
 * Entry point when the server is started as a plain process:
 *   CLASSPATH=<apk> app_process /system/bin com.mapmouse.pro.server.Main <token>
 * (launched through Shizuku, root or adb). The app connects over a localhost socket and
 * must present the token, so other apps can't drive the input server.
 */
object Main {
    /**
     * Ports the server may use; it takes the first free one and the app checks them all.
     * (A single fixed port can be taken by another app's server.)
     */
    val PORTS = 38140..38155
    const val PID_FILE = "/data/local/tmp/mapmouse_server.pid"

    /** versionCode of the app that started us, so a reinstalled app replaces an old server. */
    private var appVersion = 0
    @Volatile private var rejected = 0

    @JvmStatic
    fun main(args: Array<String>) {
        // Look for labelled arguments anywhere (phones differ in what app_process passes on),
        // then the environment.
        fun arg(name: String) = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter("=")
        val token = arg("token") ?: System.getenv("MAPMOUSE_TOKEN").orEmpty()
        appVersion = (arg("app") ?: System.getenv("MAPMOUSE_APP"))?.toIntOrNull() ?: 0
        Log.i("args: ${args.joinToString(" ") { if (it.startsWith("--token=")) "--token=…" else it }}")
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("uncaught in ${t.name}", e)
        }
        runCatching { Looper.prepareMainLooper() }
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = PORTS.firstNotNullOfOrNull { port ->
            runCatching { ServerSocket(port, 8, loopback) }.getOrNull()
        } ?: run {
            System.err.println("E all ports ${PORTS.first}-${PORTS.last} are busy")
            exitProcess(2)
        }
        val port = server.localPort
        // Lets the app stop exactly this process next time (pkill isn't reliable everywhere).
        runCatching { java.io.File(PID_FILE).writeText(android.os.Process.myPid().toString()) }
        Log.i("socket server listening on $port (pid ${android.os.Process.myPid()}, key ${token.take(6)}…, app v$appVersion)")
        val service = InputService()
        while (true) {
            val s = server.accept()
            Thread({ handle(service, token, s) }, "client").start()
        }
    }

    private fun handle(service: InputService, token: String, s: Socket) {
        try {
            s.tcpNoDelay = true
            val r = s.getInputStream().bufferedReader()
            val w = s.getOutputStream().bufferedWriter()
            val hello = JSONObject(r.readLine() ?: return)
            if (token.isNotEmpty() && hello.optString("token") != token) {
                if (rejected++ < 3) Log.e("rejected a client with key ${hello.optString("token").take(6)}… (mine is ${token.take(6)}…)")
                // Tell the client, so it knows to replace this server instead of waiting on it.
                w.write(JSONObject().put("err", "wrong token").toString()); w.newLine(); w.flush()
                return
            }
            when (hello.optString("role")) {
                "cmd" -> while (true) {
                    val line = r.readLine() ?: break
                    val reply = try {
                        JSONObject().put("r", dispatch(service, JSONObject(line)) ?: JSONObject.NULL)
                    } catch (e: Exception) {
                        JSONObject().put("err", e.toString())
                    }
                    w.write(reply.toString()); w.newLine(); w.flush()
                }
                "evt" -> {
                    service.setCallback(EventWriter(w))
                    // Block until the app disconnects, then release everything.
                    while (s.getInputStream().read() >= 0) { /* app sends nothing */ }
                    Log.i("app disconnected")
                    service.clientGone()
                }
            }
        } catch (e: Exception) {
            Log.e("client: $e")
        } finally {
            runCatching { s.close() }
        }
    }

    private fun dispatch(svc: InputService, req: JSONObject): Any? {
        val a = req.optJSONArray("a") ?: JSONArray()
        return when (req.getString("m")) {
            "version" -> svc.version() * 100000 + appVersion
            "setScreen" -> { svc.setScreen(a.getInt(0), a.getInt(1), a.getInt(2)); null }
            "setProfile" -> { svc.setProfile(a.getString(0)); null }
            "setEnabled" -> { svc.setEnabled(a.getBoolean(0)); null }
            "setPaused" -> { svc.setPaused(a.getBoolean(0)); null }
            "listDevices" -> svc.listDevices()
            "updateSettings" -> { svc.updateSettings(a.getString(0)); null }
            "setOptions" -> { svc.setOptions(a.getString(0)); null }
            "setTyping" -> { svc.setTyping(a.getBoolean(0)); null }
            "getStats" -> svc.getStats()
            "setDisplaySize" -> { svc.setDisplaySize(a.getInt(0), a.getInt(1), a.getInt(2)); null }
            "foregroundPackage" -> svc.foregroundPackage()
            "log" -> svc.log()
            "destroy" -> { svc.destroy(); null }
            else -> throw IllegalArgumentException("unknown method ${req.getString("m")}")
        }
    }

    /** Sends server events to the app as JSON lines. */
    private class EventWriter(private val w: BufferedWriter) : IServerCallback.Stub() {
        /** Never throws: a closed socket just drops the event (the reader thread cleans up). */
        private fun send(o: JSONObject) = synchronized(this) {
            try { w.write(o.toString()); w.newLine(); w.flush() } catch (_: Exception) {}
        }

        override fun onCursor(x: Int, y: Int, visible: Boolean) =
            send(JSONObject().put("e", "cursor").put("a", JSONArray().put(x).put(y).put(visible)))

        override fun onState(enabled: Boolean, cursorMode: Boolean, paused: Boolean, mice: Int, keyboards: Int) =
            send(JSONObject().put("e", "state").put("a", JSONArray().put(enabled).put(cursorMode).put(paused).put(mice).put(keyboards)))

        override fun onHud(json: String) = send(JSONObject().put("e", "hud").put("a", JSONArray().put(json)))

        override fun onPointers(xy: IntArray) =
            send(JSONObject().put("e", "pointers").put("a", JSONArray().apply { xy.forEach { put(it) } }))
    }
}
