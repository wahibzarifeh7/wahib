package com.mapmouse.pro

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StrictMode
import com.mapmouse.pro.model.Profile
import com.mapmouse.pro.server.InputService
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * App-side handle on the input server, which needs shell (or root) rights.
 *
 * Start order:
 *  1. reuse a server process that's already running (localhost socket),
 *  2. launch one as a plain `app_process` through Shizuku (or root),
 *  3. fall back to a Shizuku UserService.
 */
object ServerConnection {
    data class State(
        val connected: Boolean = false,
        val enabled: Boolean = false,
        val cursorMode: Boolean = false,
        val paused: Boolean = false,
        val mice: Int = 0,
        val keyboards: Int = 0,
    )

    interface Listener {
        fun onState(state: State) {}
        fun onCursor(x: Int, y: Int, visible: Boolean) {}
        fun onHud(hud: JSONObject) {}
        fun onPointers(xy: IntArray) {}
    }

    private const val LOG_FILE = "/data/local/tmp/mapmouse_server.log"
    private const val PROCESS_NAME = "mapmouse_server"

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = LinkedHashSet<Listener>()
    @Volatile private var service: IInputService? = null
    private var binding = false
    var state = State(); private set
    /** Last recoil/weapon/HUD state reported by the server. */
    var hud = JSONObject(); private set
    /** How the running server was started ("process" or "Shizuku UserService"). */
    var mode = ""; private set
    /** Why the last start attempt failed (null while starting or after success). */
    var startError: String? = null; private set

    /** Called after (re)connecting so the server gets the current screen and profile. */
    var onConnected: ((IInputService) -> Unit)? = null

    fun addListener(l: Listener) { listeners += l; l.onState(state) }
    fun removeListener(l: Listener) { listeners -= l }

    private fun publish(s: State) = main.post { state = s; listeners.toList().forEach { it.onState(s) } }

    private val callback = object : IServerCallback.Stub() {
        override fun onCursor(x: Int, y: Int, visible: Boolean) {
            main.post { listeners.toList().forEach { it.onCursor(x, y, visible) } }
        }

        override fun onState(enabled: Boolean, cursorMode: Boolean, paused: Boolean, mice: Int, keyboards: Int) {
            publish(State(true, enabled, cursorMode, paused, mice, keyboards))
        }

        override fun onHud(json: String) {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return
            main.post { if (!o.has("toggleBubble")) hud = o; listeners.toList().forEach { it.onHud(o) } }
        }

        override fun onPointers(xy: IntArray) {
            main.post { listeners.toList().forEach { it.onPointers(xy) } }
        }
    }

    /** Must run on the main thread. */
    private fun attach(s: IInputService, how: String) {
        binding = false
        service = s
        mode = how
        startError = null
        runCatching { onConnected?.invoke(s) }
        publish(state.copy(connected = true))
    }

    private fun failed(why: String) {
        binding = false
        startError = why
        publish(state)
    }

    // ---- Shizuku status ----

    fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun shizukuGranted(): Boolean = shizukuRunning() && !Shizuku.isPreV11() &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun shizukuUid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    private fun hasRoot() = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su").any { File(it).exists() }

    // ---- starting ----

    private val expectedVersion get() = InputService.VERSION * 100000 + BuildConfig.VERSION_CODE

    /**
     * Secret the server asks for, so other apps can't drive it. Derived from ANDROID_ID, which on
     * Android 8+ is different for every signing key, so other apps can't compute it, and it never
     * changes for us (a stored random value could be lost if the app died before saving it).
     */
    private fun token(ctx: Context): String {
        @Suppress("HardwareIds")
        val id = runCatching {
            android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull()
        if (!id.isNullOrEmpty()) {
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest("mapmouse:$id:${android.os.Process.myUid()}".toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }.take(32)
        }
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return prefs.getString("serverToken", null)
            ?: UUID.randomUUID().toString().replace("-", "").also { prefs.edit().putString("serverToken", it).commit() }
    }

    fun connect(ctx: Context) {
        if (service != null || binding) return
        // Calls to the socket server are tiny localhost round trips; allow them on the main thread.
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy()).permitNetwork().build())
        binding = true
        startError = null
        val app = ctx.applicationContext
        io.execute {
            val err = try { startProcessServer(app) } catch (t: Throwable) { "Start failed: $t" }
            if (err == null) return@execute
            main.post {
                if (shizukuGranted()) bindUserService(app, err) else failed(err)
            }
        }
    }

    /** Connects to a running server with the right version; runs on [io]. */
    private fun tryAttach(token: String): Boolean {
        for (port in com.mapmouse.pro.server.Main.PORTS) {
            val c = SocketClient(token, port)
            if (!c.open()) continue
            val v = try {
                c.version()
            } catch (e: Exception) {
                // Another app's server, or ours from another install (wrong key): skip it.
                c.close(); continue
            }
            if (v != expectedVersion) {
                // Our server from a previous app version: stop it.
                c.destroy(); c.close()
                Thread.sleep(300)
                continue
            }
            c.readTimeoutMs = 10_000
            if (attachSocket(c)) return true
        }
        return false
    }

    private fun attachSocket(c: SocketClient): Boolean {
        try {
            c.setCallback(callback)
        } catch (e: Exception) {
            c.close(); return false
        }
        c.onClosed = { main.post { if (service === c) { service = null; mode = ""; publish(State()) } } }
        main.post { attach(c, "process") }
        return true
    }

    /** Returns null when connected, otherwise what went wrong. Runs on [io]. */
    private fun startProcessServer(ctx: Context): String? {
        val token = token(ctx)
        if (tryAttach(token)) return null
        val launcher = when {
            shizukuGranted() -> "Shizuku"
            hasRoot() -> "root"
            else -> return "Shizuku isn't running, or MapMouse Pro doesn't have its permission yet."
        }
        val apk = ctx.applicationInfo.sourceDir
        // Token and version go in labelled arguments and the environment, so the server finds them
        // however this phone's app_process passes arguments.
        // The class name is assembled from $P inside the shell, so this shell's own command line
        // never contains "com.mapmouse.pro.server.Main" — otherwise the pkill -f below would kill
        // the very shell that's about to start the server (that was the 2.2.1–2.2.4 bug).
        val start = "app_process /system/bin --nice-name=$PROCESS_NAME " +
            "\$P.server.Main --token=$token --app=${BuildConfig.VERSION_CODE}"
        val env = "MAPMOUSE_TOKEN=$token MAPMOUSE_APP=${BuildConfig.VERSION_CODE} CLASSPATH=$apk"
        // Stop any old server (pid file, process name, class name), remove the old log so a dying
        // server can't mix its lines in, then start detached (setsid when available).
        // CLASSPATH must come first: `setsid CLASSPATH=…` would treat the assignment as the program name.
        val pid = com.mapmouse.pro.server.Main.PID_FILE
        val cmd = "P=com.mapmouse.pro; " +
            "[ -f $pid ] && kill \$(cat $pid) 2>/dev/null; " +
            "pkill -x $PROCESS_NAME; pkill -f \"\$P.server.Main\"; sleep 0.5; " +
            "rm -f $LOG_FILE $pid; S=\$(command -v setsid); " +
            "($env \$S $start > $LOG_FILE 2>&1 < /dev/null &); true"
        val run = shell(launcher, cmd)
        if (run == null) return "[MapMouse Pro ${BuildConfig.VERSION_NAME}] Couldn't run the start command through $launcher.\n\n$shellError\n\n${diagnostics()}"
        // Wait up to ~15 s for the server to come up (the first start compiles the app's code).
        repeat(60) {
            Thread.sleep(250)
            if (tryAttach(token)) return null
        }
        val out = shell(launcher, "head -c 1500 $LOG_FILE")?.second?.trim()
        val procs = shell(launcher, "ps -A -o PID,NAME,ARGS | grep -i mapmouse | grep -v grep")?.second?.trim()
        return "[MapMouse Pro ${BuildConfig.VERSION_NAME}] The input server didn't start." +
            (if (!out.isNullOrEmpty()) "\n\nServer output:\n$out" else "\n\nStart command output:\n${run.second.trim().ifEmpty { "(none)" }}") +
            "\n\nApp key ${token.take(6)}…, app v${BuildConfig.VERSION_CODE}" +
            "\nServer processes:\n${procs?.take(800)?.ifEmpty { "(none)" }}" +
            "\n\n${diagnostics()}"
    }

    /** Details for bug reports: Shizuku version/uid and whether a trivial command works. */
    private fun diagnostics(): String {
        val ver = runCatching { Shizuku.getVersion().toString() }.getOrElse { "? ($it)" }
        val id = shell("Shizuku", "id")?.second?.trim()?.substringBefore(" groups") ?: "failed: $shellError"
        return "Shizuku server v$ver, Android ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MODEL}\n`id` → $id"
    }

    /** Why the last [shell] call failed. */
    @Volatile private var shellError = ""

    /** Runs a shell command with shell (Shizuku) or root rights; returns exit code and output. */
    private fun shell(launcher: String, cmd: String, timeoutSec: Long = 6): Pair<Int, String>? = try {
        val p: Process = if (launcher == "Shizuku") {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        } else Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = StringBuffer()
        val readers = listOf(p.inputStream, p.errorStream).map { s ->
            Thread { runCatching { s.bufferedReader().forEachLine { out.append(it).append('\n') } } }.apply { start() }
        }
        // Plain waitFor() on a helper thread: the timed variant isn't supported by every Shizuku version.
        var code = -1
        val waiter = Thread { runCatching { code = p.waitFor() } }.apply { start() }
        waiter.join(timeoutSec * 1000)
        if (waiter.isAlive) runCatching { p.destroy() }
        readers.forEach { it.join(500) }
        code to out.toString()
    } catch (t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        shellError = "Error: $cause"
        android.util.Log.e("MapMouse", "shell via $launcher failed", cause)
        null
    }

    // ---- Shizuku UserService fallback ----

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            val s = IInputService.Stub.asInterface(binder)
            runCatching { s.setCallback(callback) }
            attach(s, "Shizuku UserService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            mode = ""
            publish(State())
        }
    }

    // Not debuggable: Shizuku then adds JDWP flags to the server's command line, which
    // fails to start on some phones.
    private fun args(ctx: Context) = Shizuku.UserServiceArgs(
        ComponentName(ctx.packageName, InputService::class.java.name)
    ).daemon(false)
        .processNameSuffix("input")
        .debuggable(false)
        .version(InputService.VERSION + BuildConfig.VERSION_CODE * 1000)

    private fun bindUserService(ctx: Context, firstError: String) {
        runCatching { Shizuku.bindUserService(args(ctx), conn) }.onFailure {
            failed("$firstError\n\nFallback (Shizuku UserService) also failed: $it")
            return
        }
        // If the server process dies while starting, Shizuku never calls back: don't wait forever.
        main.postDelayed({
            if (binding && service == null) failed("$firstError\n\nFallback (Shizuku UserService) didn't start either.")
        }, 10_000)
    }

    /** Kills any old server process and starts a fresh one. */
    fun restart(ctx: Context) {
        stop(ctx)
        main.postDelayed({ connect(ctx) }, 800)
    }

    /** Stops the server process entirely (releases any grabbed devices). */
    fun stop(ctx: Context) {
        val s = service
        service = null
        binding = false
        if (s is SocketClient) io.execute { runCatching { s.destroy() }; s.close() }
        else {
            runCatching { s?.setEnabled(false) }
            runCatching { Shizuku.unbindUserService(args(ctx), conn, true) }
        }
        mode = ""
        publish(State())
    }

    fun serverLog(): String? = runCatching { service?.log() }.getOrNull()

    private inline fun call(block: (IInputService) -> Unit) {
        val s = service ?: return
        try { block(s) } catch (e: Exception) { service = null; publish(State()) }
    }

    fun setProfile(p: Profile) = call { it.setProfile(p.toJson().toString()) }
    fun setScreen(w: Int, h: Int, rotation: Int) = call { it.setScreen(w, h, rotation) }
    fun setOptions(json: JSONObject) = call { it.setOptions(json.toString()) }
    fun setTyping(on: Boolean) = call { it.setTyping(on) }
    fun stats(): JSONObject? = runCatching { service?.stats?.let { JSONObject(it) } }.getOrNull()
    fun setDisplaySize(w: Int, h: Int, density: Int) = call { it.setDisplaySize(w, h, density) }
    fun foregroundPackage(): String? = runCatching { service?.foregroundPackage() }.getOrNull()
    fun setEnabled(on: Boolean) = call { it.setEnabled(on) }
    fun setPaused(on: Boolean) = call { it.setPaused(on) }
    fun updateSettings(p: Profile) = call { it.updateSettings(p.toJson().toString()) }
    fun listDevices(): String? = runCatching { service?.listDevices() }.getOrNull()
}
