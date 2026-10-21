package com.mapmouse.pro.server

import android.os.IBinder
import android.os.RemoteException
import com.mapmouse.pro.IInputService
import com.mapmouse.pro.IServerCallback
import com.mapmouse.pro.model.Profile
import org.json.JSONArray
import org.json.JSONObject
import kotlin.system.exitProcess

/**
 * The input server. It runs as the shell user (or root), which is what lets it read
 * /dev/input and inject touches. It's started either as a plain app_process
 * (see [Main], talking to the app over a localhost socket) or as a Shizuku UserService.
 */
class InputService : IInputService.Stub() {

    @Volatile private var callback: IServerCallback? = null
    private val deathRecipient = IBinder.DeathRecipient { clientGone() }

    /** App died or disconnected: give the keyboard, mouse and display back to Android. */
    fun clientGone() {
        callback = null
        engine.setEnabled(false)
        engine.setPaused(false)
        if (displayChanged) setDisplaySize(0, 0, 0)
    }

    private val listener = object : Engine.Listener {
        override fun onCursor(x: Int, y: Int, visible: Boolean) {
            try { callback?.onCursor(x, y, visible) } catch (_: RemoteException) {}
        }

        override fun onState(enabled: Boolean, cursorMode: Boolean, paused: Boolean, mice: Int, keyboards: Int) {
            try { callback?.onState(enabled, cursorMode, paused, mice, keyboards) } catch (_: RemoteException) {}
        }

        override fun onHud(json: String) {
            try { callback?.onHud(json) } catch (_: RemoteException) {}
        }

        override fun onPointers(xy: IntArray) {
            try { callback?.onPointers(xy) } catch (_: RemoteException) {}
        }
    }

    private val touch = Touch()
    private val stats = Stats()
    private lateinit var devices: InputDevices
    private val engine: Engine = Engine(
        touch,
        devices = { devices.miceCount to devices.keyboardCount },
        onGrab = { devices.grab = it },
        listener = listener,
    )
    @Volatile private var displayChanged = false

    init {
        Log.i("input server starting (uid ${android.os.Process.myUid()}, Android ${android.os.Build.VERSION.SDK_INT})")
        devices = InputDevices(engine)
        // Never let a start-up problem kill the service: the app could then only show "starting…".
        try {
            devices.start()
            engine.start()
            Thread({
                var last = -1 to -1
                while (true) {
                    Thread.sleep(1500)
                    val now = devices.miceCount to devices.keyboardCount
                    if (now != last) { last = now; engine.sendState() }
                }
            }, "device-watch").apply { isDaemon = true }.start()
            val found = InputDevices.parseDevices()
            Log.i("input devices: " + found.joinToString { "${it.name} (${it.path})" }.ifEmpty { "none readable" })
        } catch (t: Throwable) {
            Log.e("start-up error", t)
        }
    }

    override fun log(): String = Log.dump()

    override fun destroy() {
        devices.stop()
        if (displayChanged) setDisplaySize(0, 0, 0)
        exitProcess(0)
    }

    override fun version(): Int = VERSION

    override fun setCallback(cb: IServerCallback?) {
        callback?.asBinder()?.runCatching { unlinkToDeath(deathRecipient, 0) }
        callback = cb
        cb?.asBinder()?.linkToDeath(deathRecipient, 0)
        engine.sendState()
    }

    override fun setScreen(width: Int, height: Int, rotation: Int) = engine.setScreen(width, height, rotation)

    override fun setProfile(json: String) = engine.setProfile(Profile.fromJson(JSONObject(json)))

    override fun setEnabled(on: Boolean) = engine.setEnabled(on)

    override fun setPaused(on: Boolean) = engine.setPaused(on)

    override fun updateSettings(json: String) = engine.updateSettings(Profile.fromJson(JSONObject(json)))

    override fun setOptions(json: String) {
        val o = Options.fromJson(JSONObject(json))
        devices.includeTouch = o.touchCompat
        engine.setOptions(o)
    }

    override fun setTyping(on: Boolean) = engine.setTyping(on)

    override fun getStats(): String = stats.read().toString()

    override fun setDisplaySize(width: Int, height: Int, density: Int) {
        if (width > 0 && height > 0) { Shell.run("wm size ${width}x$height"); displayChanged = true }
        else Shell.run("wm size reset")
        if (density > 0) { Shell.run("wm density $density"); displayChanged = true }
        else Shell.run("wm density reset")
        if (width <= 0 && density <= 0) displayChanged = false
    }

    override fun foregroundPackage(): String {
        val out = Shell.run("dumpsys activity activities | grep -m 1 -E 'topResumedActivity|mResumedActivity'") ?: return ""
        return Regex("""u\d+ ([\w.]+)/""").find(out)?.groupValues?.get(1) ?: ""
    }

    override fun listDevices(): String = JSONArray().apply {
        InputDevices.parseDevices().forEach {
            put(JSONObject().put("name", it.name).put("path", it.path).put("mouse", it.mouse)
                .put("keyboard", it.keyboard).put("pad", it.pad).put("touch", it.touch))
        }
    }.toString()

    companion object {
        /** Bump when the server code changes so Shizuku restarts the old process. */
        const val VERSION = 5
    }
}
