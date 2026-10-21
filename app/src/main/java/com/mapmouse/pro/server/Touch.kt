package com.mapmouse.pro.server

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Multi-touch injector. Runs inside the shell-privileged server process, where
 * InputManager.injectInputEvent() is allowed (INJECT_EVENTS is granted to shell).
 */
class Touch {
    private var inputManager: Any? = null
    private var injectMethod: Method? = null

    /** Looks up the hidden injection API on first use, so a failure can't stop the server from starting. */
    private fun resolve(): Boolean {
        if (injectMethod != null) return true
        try {
            val global = runCatching {
                val cls = Class.forName("android.hardware.input.InputManagerGlobal")
                val obj = cls.getMethod("getInstance").invoke(null)!!
                obj to cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            }.getOrNull()
            val pair = global ?: run {
                val cls = Class.forName("android.hardware.input.InputManager")
                val get = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }
                get.invoke(null)!! to cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            }
            inputManager = pair.first
            injectMethod = pair.second
            Log.i("touch injection ready (${pair.first.javaClass.simpleName})")
            return true
        } catch (t: Throwable) {
            Log.e("touch injection unavailable", t)
            return false
        }
    }

    private class Pointer(val id: Int, var x: Float, var y: Float)

    private val pointers = ArrayList<Pointer>()
    private var downTime = 0L

    @Synchronized
    fun down(x: Float, y: Float): Int {
        val used = pointers.map { it.id }.toSet()
        val id = (0 until MAX_POINTERS).firstOrNull { it !in used } ?: return -1
        if (pointers.isEmpty()) downTime = SystemClock.uptimeMillis()
        pointers += Pointer(id, x, y)
        val index = pointers.size - 1
        val action = if (pointers.size == 1) MotionEvent.ACTION_DOWN
        else MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        emit(action)
        return id
    }

    @Synchronized
    fun move(id: Int, x: Float, y: Float) {
        val p = pointers.firstOrNull { it.id == id } ?: return
        p.x = x; p.y = y
        emit(MotionEvent.ACTION_MOVE)
    }

    @Synchronized
    fun up(id: Int) {
        val index = pointers.indexOfFirst { it.id == id }
        if (index < 0) return
        val action = if (pointers.size == 1) MotionEvent.ACTION_UP
        else MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        emit(action)
        pointers.removeAt(index)
    }

    @Synchronized
    fun isDown(id: Int) = pointers.any { it.id == id }

    /** Current pointers as x0,y0,x1,y1,… (for the "show touch paths" overlay). */
    @Synchronized
    fun snapshot(): IntArray = IntArray(pointers.size * 2) { i ->
        val p = pointers[i / 2]
        (if (i % 2 == 0) p.x else p.y).toInt()
    }

    @Synchronized
    fun releaseAll() {
        while (pointers.isNotEmpty()) up(pointers.last().id)
    }

    private var injectErrors = 0

    private fun emit(action: Int) {
        if (!resolve()) return
        val n = pointers.size
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().apply {
                id = pointers[i].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().apply {
                x = pointers[i].x; y = pointers[i].y
                pressure = 1f; size = 1f
            }
        }
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(downTime, now, action, n, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            injectMethod!!.invoke(inputManager, ev, INJECT_ASYNC)
        } catch (e: Exception) {
            // Log the first few only; a broken injector would otherwise flood the log.
            if (injectErrors++ < 5) Log.e("inject failed", e.cause ?: e)
        } finally {
            ev.recycle()
        }
    }

    companion object {
        const val MAX_POINTERS = 10
        private const val INJECT_ASYNC = 0
    }
}
