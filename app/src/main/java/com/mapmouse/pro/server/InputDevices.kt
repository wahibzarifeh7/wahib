package com.mapmouse.pro.server

import android.system.Os
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Receives decoded events from physical keyboards, mice, gamepads and (optionally) the touchscreen. */
interface InputSink {
    fun onKey(code: Int, down: Boolean)
    fun onMove(dx: Int, dy: Int)
    fun onWheel(steps: Int)
    /** Gamepad stick: 0 = left, 1 = right; x/y in -1..1. */
    fun onStick(stick: Int, x: Float, y: Float)
    /** Real finger on the touchscreen, coordinates normalized in the panel's natural orientation. */
    fun onFinger(slot: Int, down: Boolean, nx: Float, ny: Float)
}

/**
 * Reads /dev/input/event* directly (the shell user is in the `input` group).
 * While grabbed, devices are opened with EVIOCGRAB so Android stops seeing them:
 * the game only receives our injected touches.
 */
class InputDevices(private val sink: InputSink) {
    class Info(
        val path: String, val name: String,
        val mouse: Boolean, val keyboard: Boolean, val pad: Boolean, val touch: Boolean,
    )

    private class Range(val min: Int, val max: Int) {
        fun norm(v: Int) = ((v - min).toFloat() / (max - min).coerceAtLeast(1)).coerceIn(0f, 1f)
        fun axis(v: Int) = norm(v) * 2f - 1f
    }

    @Volatile var grab = Grab(false, false, false, false)
        set(value) {
            if (field == value) return
            field = value
            synchronized(readers) { readers.values.forEach { it.syncGrab() } }
        }

    /** Read the touchscreen too (touch-compatible mode). */
    @Volatile var includeTouch = false

    private val readers = HashMap<String, Reader>()
    @Volatile private var running = true

    val miceCount get() = synchronized(readers) { readers.values.count { it.info.mouse } }
    val keyboardCount get() = synchronized(readers) { readers.values.count { it.info.keyboard } }

    fun start() {
        Thread({
            while (running) {
                runCatching { scan() }.onFailure { Log.e("scan: $it") }
                Thread.sleep(2000)
            }
        }, "input-scan").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        synchronized(readers) { readers.values.forEach { it.close() }; readers.clear() }
    }

    private fun wanted(i: Info) = i.mouse || i.keyboard || i.pad || (i.touch && includeTouch)

    private fun scan() {
        val found = parseDevices().filter { wanted(it) }.associateBy { it.path }
        synchronized(readers) {
            readers.keys.filter { it !in found || !readers[it]!!.alive }.forEach { readers.remove(it)?.close() }
            for ((path, info) in found) if (path !in readers) {
                Log.i("device: ${info.name} ($path) mouse=${info.mouse} kbd=${info.keyboard} pad=${info.pad} touch=${info.touch}")
                readers[path] = Reader(info).also { it.start() }
            }
        }
    }

    private fun shouldGrab(i: Info) = when {
        i.touch -> grab.touch
        i.pad -> grab.pad
        i.keyboard -> grab.keyboard
        i.mouse -> grab.mouse
        else -> false
    }

    private inner class Reader(val info: Info) {
        @Volatile var alive = true
        @Volatile private var stream: FileInputStream? = null
        @Volatile private var grabbed = false
        private val eventSize = if (android.os.Process.is64Bit()) 24 else 16
        private val ranges: Map<Int, Range> = if (info.pad || info.touch) absRanges(info.path) else emptyMap()

        fun start() = Thread(::loop, "input-${info.path.substringAfterLast('/')}").apply { isDaemon = true }.start()

        /** EVIOCGRAB via our ioctl helper can only grab, so releasing means reopening. */
        fun syncGrab() { if (shouldGrab(info) != grabbed) runCatching { stream?.close() } }

        fun close() { alive = false; runCatching { stream?.close() } }

        private fun loop() {
            var failures = 0
            while (alive && running) {
                try {
                    val s = FileInputStream(info.path)
                    stream = s
                    grabbed = shouldGrab(info) && ioctlGrab(s.fd).also { if (!it) Log.e("EVIOCGRAB failed on ${info.path}") }
                    failures = 0
                    readEvents(s)
                } catch (e: IOException) {
                    if (!alive || !running) break
                    if (!File(info.path).exists() || ++failures > 5) break // unplugged
                    Thread.sleep(100)
                } finally {
                    runCatching { stream?.close() }
                    grabbed = false
                }
            }
            alive = false
        }

        private fun readEvents(s: FileInputStream) {
            val buf = ByteArray(eventSize * 64)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder())
            var dx = 0; var dy = 0; var wheel = 0
            // gamepad state
            val abs = HashMap<Int, Int>()
            var absDirty = false
            var hatX = 0; var hatY = 0
            var trigL = false; var trigR = false
            // touchscreen state (multi-touch protocol B)
            var slot = 0
            val slotId = HashMap<Int, Int>()
            val slotX = HashMap<Int, Int>()
            val slotY = HashMap<Int, Int>()
            val dirtySlots = HashSet<Int>()
            val rx = ranges[ABS_MT_POSITION_X]; val ry = ranges[ABS_MT_POSITION_Y]

            fun axis(code: Int) = ranges[code]?.let { r -> abs[code]?.let { r.axis(it) } } ?: 0f

            while (true) {
                val n = s.read(buf)
                if (n <= 0) throw IOException("eof")
                var off = 0
                while (off + eventSize <= n) {
                    val base = off + eventSize - 8
                    val type = bb.getShort(base).toInt() and 0xffff
                    val code = bb.getShort(base + 2).toInt() and 0xffff
                    val value = bb.getInt(base + 4)
                    off += eventSize
                    when (type) {
                        EV_KEY -> if (value != 2 && !info.touch) sink.onKey(code, value == 1) // ignore autorepeat
                        EV_REL -> when (code) {
                            REL_X -> dx += value
                            REL_Y -> dy += value
                            REL_WHEEL -> wheel += value
                        }
                        EV_ABS -> if (info.touch) when (code) {
                            ABS_MT_SLOT -> slot = value
                            ABS_MT_TRACKING_ID -> { if (value < 0) slotId.remove(slot) else slotId[slot] = value; dirtySlots += slot }
                            ABS_MT_POSITION_X -> { slotX[slot] = value; dirtySlots += slot }
                            ABS_MT_POSITION_Y -> { slotY[slot] = value; dirtySlots += slot }
                        } else when (code) {
                            ABS_HAT0X -> {
                                if (value != hatX) {
                                    if (hatX < 0) sink.onKey(DPAD_LEFT, false); if (hatX > 0) sink.onKey(DPAD_RIGHT, false)
                                    if (value < 0) sink.onKey(DPAD_LEFT, true); if (value > 0) sink.onKey(DPAD_RIGHT, true)
                                    hatX = value
                                }
                            }
                            ABS_HAT0Y -> {
                                if (value != hatY) {
                                    if (hatY < 0) sink.onKey(DPAD_UP, false); if (hatY > 0) sink.onKey(DPAD_DOWN, false)
                                    if (value < 0) sink.onKey(DPAD_UP, true); if (value > 0) sink.onKey(DPAD_DOWN, true)
                                    hatY = value
                                }
                            }
                            else -> { abs[code] = value; absDirty = true }
                        }
                        EV_SYN -> {
                            if (dx != 0 || dy != 0) sink.onMove(dx, dy)
                            if (wheel != 0) sink.onWheel(wheel)
                            dx = 0; dy = 0; wheel = 0
                            if (absDirty) {
                                absDirty = false
                                sink.onStick(0, axis(ABS_X), axis(ABS_Y))
                                // Right stick is RX/RY on most pads, Z/RZ on others (then triggers are GAS/BRAKE).
                                val rightOnR = ABS_RX in ranges && ABS_RY in ranges && ABS_Z in ranges
                                if (rightOnR) sink.onStick(1, axis(ABS_RX), axis(ABS_RY))
                                else sink.onStick(1, axis(ABS_Z), axis(ABS_RZ))
                                val lt = if (rightOnR) axis(ABS_Z) else axis(ABS_BRAKE)
                                val rt = if (rightOnR) axis(ABS_RZ) else axis(ABS_GAS)
                                val l = lt > 0f; val r = rt > 0f
                                if (l != trigL) { trigL = l; sink.onKey(TRIGGER_L, l) }
                                if (r != trigR) { trigR = r; sink.onKey(TRIGGER_R, r) }
                            }
                            if (dirtySlots.isNotEmpty() && rx != null && ry != null) {
                                for (sl in dirtySlots) {
                                    val down = sl in slotId
                                    sink.onFinger(sl, down, rx.norm(slotX[sl] ?: 0), ry.norm(slotY[sl] ?: 0))
                                }
                                dirtySlots.clear()
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EV_SYN = 0
        private const val EV_KEY = 1
        private const val EV_REL = 2
        private const val EV_ABS = 3
        private const val REL_X = 0
        private const val REL_Y = 1
        private const val REL_WHEEL = 8
        private const val ABS_X = 0
        private const val ABS_Y = 1
        private const val ABS_Z = 2
        private const val ABS_RX = 3
        private const val ABS_RY = 4
        private const val ABS_RZ = 5
        private const val ABS_GAS = 9
        private const val ABS_BRAKE = 10
        private const val ABS_HAT0X = 0x10
        private const val ABS_HAT0Y = 0x11
        private const val ABS_MT_SLOT = 0x2f
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39
        private const val BTN_SOUTH = 0x130
        private const val EVIOCGRAB = 0x40044590
        private const val DPAD_UP = 0x2f2
        private const val DPAD_DOWN = 0x2f3
        private const val DPAD_LEFT = 0x2f4
        private const val DPAD_RIGHT = 0x2f5
        private const val TRIGGER_L = 0x2f6
        private const val TRIGGER_R = 0x2f7

        /** Width of the kernel's `long`, which is how /proc prints capability bitmaps. */
        private val longBits: Int by lazy {
            val m = runCatching { Os.uname().machine }.getOrDefault("")
            if (m.contains("64") || m.startsWith("armv8")) 64 else 32
        }

        private fun hasBit(mask: String, bit: Int): Boolean {
            val words = mask.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val idx = words.size - 1 - bit / longBits
            if (idx < 0) return false
            val word = words[idx].toULongOrNull(16) ?: return false
            return (word shr (bit % longBits)) and 1uL == 1uL
        }

        /** Finds and classifies mice, keyboards, gamepads and touchscreens. */
        fun parseDevices(): List<Info> {
            // /proc/bus/input/devices is blocked for the shell on some phones (e.g. Infinix XOS);
            // `getevent -p` works everywhere the shell can read /dev/input.
            val viaGetevent = Shell.run("getevent -p")?.let { parseGetevent(it) }
            return if (!viaGetevent.isNullOrEmpty()) viaGetevent else parseProc()
        }

        /**
         * Parses `getevent -p` output:
         *   add device 1: /dev/input/event5
         *     name:     "mtk-tpd"
         *     events:
         *       KEY (0001): 0074  00b7 …        (codes may continue on following lines)
         *       ABS (0003): 0035  : value 0, min 0, max 17279, …
         *     input props:
         *       INPUT_PROP_DIRECT
         */
        private fun parseGetevent(text: String): List<Info> {
            val out = ArrayList<Info>()
            for (block in text.split("add device").drop(1)) {
                val path = Regex("""(/dev/input/event\d+)""").find(block)?.value ?: continue
                val name = Regex("""name:\s*"(.*)"""").find(block)?.groupValues?.get(1).orEmpty()
                val codes = HashMap<String, MutableSet<Int>>()
                var section = ""
                var inEvents = false
                for (line in block.lines()) {
                    val t = line.trim()
                    when {
                        t.startsWith("events:") -> { inEvents = true; continue }
                        t.startsWith("input props:") -> { inEvents = false; section = "PROPS"; continue }
                    }
                    if (section == "PROPS") {
                        if ("INPUT_PROP_DIRECT" in t) codes.getOrPut("PROPS") { HashSet() } += 1
                        continue
                    }
                    if (!inEvents) continue
                    val head = Regex("""^([A-Z]+)\s*\(([0-9a-f]{4})\):\s*(.*)$""").find(t)
                    val rest = if (head != null) { section = head.groupValues[1]; head.groupValues[3] } else t
                    val set = codes.getOrPut(section) { HashSet() }
                    if (section == "ABS") {
                        // One axis per line: "0035  : value …"
                        Regex("""^([0-9a-f]{4})\s*:""").find(rest)?.let { set += it.groupValues[1].toInt(16) }
                    } else {
                        Regex("""\b([0-9a-f]{4})\b""").findAll(rest).forEach { set += it.groupValues[1].toInt(16) }
                    }
                }
                val key = codes["KEY"].orEmpty(); val rel = codes["REL"].orEmpty(); val abs = codes["ABS"].orEmpty()
                val mouse = REL_X in rel && REL_Y in rel
                val keyboard = (16..25).all { it in key }
                val touch = ABS_MT_POSITION_X in abs && ABS_MT_POSITION_Y in abs && 1 in codes["PROPS"].orEmpty()
                val pad = !touch && BTN_SOUTH in key && ABS_X in abs && ABS_Y in abs
                if (!mouse && !keyboard && !touch && !pad) continue
                out += Info(path, name, mouse, keyboard && !touch, pad, touch)
            }
            return out
        }

        /** Parses /proc/bus/input/devices (where readable). */
        private fun parseProc(): List<Info> {
            val out = ArrayList<Info>()
            val text = runCatching { File("/proc/bus/input/devices").readText() }.getOrElse { return out }
            for (block in text.split(Regex("\\n\\s*\\n"))) {
                var name = ""; var handlers = ""; var ev = ""
                var key = ""; var rel = ""; var abs = ""; var prop = ""
                for (line in block.lines()) {
                    when {
                        line.startsWith("N: Name=") -> name = line.substringAfter("=").trim('"')
                        line.startsWith("H: Handlers=") -> handlers = line.substringAfter("=")
                        line.startsWith("B: EV=") -> ev = line.substringAfter("=")
                        line.startsWith("B: KEY=") -> key = line.substringAfter("=")
                        line.startsWith("B: REL=") -> rel = line.substringAfter("=")
                        line.startsWith("B: ABS=") -> abs = line.substringAfter("=")
                        line.startsWith("B: PROP=") -> prop = line.substringAfter("=")
                    }
                }
                val event = Regex("event\\d+").find(handlers)?.value ?: continue
                val mouse = hasBit(ev, EV_REL) && hasBit(rel, REL_X) && hasBit(rel, REL_Y)
                // Real keyboards have the full Q..P row (codes 16..25).
                val keyboard = hasBit(ev, EV_KEY) && (16..25).all { hasBit(key, it) }
                val touch = hasBit(abs, ABS_MT_POSITION_X) && hasBit(abs, ABS_MT_POSITION_Y) &&
                    (prop.isBlank() || hasBit(prop, 1)) // INPUT_PROP_DIRECT = a screen, not a touchpad
                val pad = !touch && hasBit(key, BTN_SOUTH) && hasBit(abs, ABS_X) && hasBit(abs, ABS_Y)
                if (!mouse && !keyboard && !touch && !pad) continue
                out += Info("/dev/input/$event", name, mouse, keyboard && !touch, pad, touch)
            }
            return out
        }

        /** Axis ranges from `getevent -p` (shell can run it; Java has no EVIOCGABS). */
        private fun absRanges(path: String): Map<Int, Range> {
            val out = HashMap<Int, Range>()
            val text = Shell.run("getevent -p $path") ?: return out
            val re = Regex("([0-9a-f]{4})\\s*:\\s*value -?\\d+, min (-?\\d+), max (-?\\d+)")
            for (m in re.findAll(text)) {
                out[m.groupValues[1].toInt(16)] = Range(m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }
            return out
        }

        /**
         * EVIOCGRAB via the platform's ioctlInt (hidden, so reflection). Every variant
         * passes a non-null pointer as the argument, which the kernel treats as "grab".
         */
        fun ioctlGrab(fd: FileDescriptor): Boolean {
            for (m in Os::class.java.declaredMethods) {
                if (m.name != "ioctlInt") continue
                val p = m.parameterTypes
                val args = arrayOfNulls<Any>(p.size)
                var ok = true
                for (i in p.indices) args[i] = when {
                    p[i] == FileDescriptor::class.java -> fd
                    p[i] == Int::class.javaPrimitiveType && i == 1 -> EVIOCGRAB
                    else -> runCatching {
                        p[i].getConstructor(Int::class.javaPrimitiveType).newInstance(1)
                    }.getOrElse { ok = false; null }
                }
                if (!ok) continue
                try {
                    m.isAccessible = true
                    m.invoke(null, *args)
                    return true
                } catch (e: Exception) {
                    Log.e("ioctlInt: ${e.cause ?: e}")
                }
            }
            return false
        }
    }
}
