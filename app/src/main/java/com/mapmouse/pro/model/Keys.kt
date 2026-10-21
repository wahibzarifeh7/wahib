package com.mapmouse.pro.model

/**
 * Key identifiers are Linux input event codes (what /dev/input reports and what
 * Android exposes as KeyEvent.getScanCode()), plus two virtual codes for the wheel.
 */
object Keys {
    const val NONE = -1

    const val KEY_ESC = 1
    const val KEY_TAB = 15
    const val KEY_ENTER = 28
    const val KEY_LEFTCTRL = 29
    const val KEY_GRAVE = 41
    const val KEY_LEFTSHIFT = 42
    const val KEY_LEFTALT = 56
    const val KEY_SPACE = 57
    const val KEY_F9 = 67
    const val KEY_F12 = 88
    const val KEY_SCROLLLOCK = 70
    const val KEY_PAUSE = 119

    /** F1..F12 in order. */
    val F_KEYS = listOf(59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 87, 88)
    const val KEY_RIGHTCTRL = 97
    const val KEY_Z = 44

    const val KEY_Q = 16; const val KEY_W = 17; const val KEY_E = 18; const val KEY_R = 19
    const val KEY_A = 30; const val KEY_S = 31; const val KEY_D = 32; const val KEY_F = 33
    const val KEY_C = 46

    const val BTN_LEFT = 0x110
    const val BTN_RIGHT = 0x111
    const val BTN_MIDDLE = 0x112
    const val BTN_SIDE = 0x113
    const val BTN_EXTRA = 0x114

    const val WHEEL_UP = 0x2f0
    const val WHEEL_DOWN = 0x2f1

    // Gamepad buttons (Linux BTN_* codes) and virtual codes for the D-pad and analog triggers.
    const val BTN_SOUTH = 0x130
    const val BTN_THUMBR = 0x13e
    const val DPAD_UP = 0x2f2
    const val DPAD_DOWN = 0x2f3
    const val DPAD_LEFT = 0x2f4
    const val DPAD_RIGHT = 0x2f5
    const val TRIGGER_L = 0x2f6
    const val TRIGGER_R = 0x2f7

    fun isGamepad(code: Int) = code in BTN_SOUTH..BTN_THUMBR || code in DPAD_UP..TRIGGER_R

    private val names: Map<Int, String> = buildMap {
        put(1, "Esc")
        "1234567890".forEachIndexed { i, c -> put(2 + i, c.toString()) }
        put(12, "-"); put(13, "="); put(14, "Bksp"); put(15, "Tab")
        "QWERTYUIOP".forEachIndexed { i, c -> put(16 + i, c.toString()) }
        put(26, "["); put(27, "]"); put(28, "Enter"); put(29, "LCtrl")
        "ASDFGHJKL".forEachIndexed { i, c -> put(30 + i, c.toString()) }
        put(39, ";"); put(40, "'"); put(41, "`"); put(42, "LShift"); put(43, "\\")
        "ZXCVBNM".forEachIndexed { i, c -> put(44 + i, c.toString()) }
        put(51, ","); put(52, "."); put(53, "/"); put(54, "RShift"); put(55, "Num*")
        put(56, "LAlt"); put(57, "Space"); put(58, "Caps")
        for (i in 0 until 10) put(59 + i, "F${i + 1}")
        put(69, "NumLk"); put(70, "ScrLk")
        put(71, "Num7"); put(72, "Num8"); put(73, "Num9"); put(74, "Num-")
        put(75, "Num4"); put(76, "Num5"); put(77, "Num6"); put(78, "Num+")
        put(79, "Num1"); put(80, "Num2"); put(81, "Num3"); put(82, "Num0"); put(83, "Num.")
        put(87, "F11"); put(88, "F12"); put(96, "NumEnt"); put(97, "RCtrl"); put(98, "Num/")
        put(100, "RAlt"); put(102, "Home"); put(103, "Up"); put(104, "PgUp"); put(105, "Left")
        put(106, "Right"); put(107, "End"); put(108, "Down"); put(109, "PgDn")
        put(110, "Ins"); put(111, "Del"); put(119, "Pause"); put(125, "Meta")
        put(BTN_LEFT, "LMB"); put(BTN_RIGHT, "RMB"); put(BTN_MIDDLE, "MMB")
        put(BTN_SIDE, "M4"); put(BTN_EXTRA, "M5")
        put(WHEEL_UP, "Wheel↑"); put(WHEEL_DOWN, "Wheel↓")
        put(0x130, "Pad A"); put(0x131, "Pad B"); put(0x133, "Pad X"); put(0x134, "Pad Y")
        put(0x136, "Pad LB"); put(0x137, "Pad RB"); put(0x138, "Pad LT"); put(0x139, "Pad RT")
        put(0x13a, "Pad Select"); put(0x13b, "Pad Start"); put(0x13c, "Pad Home")
        put(0x13d, "Pad L3"); put(0x13e, "Pad R3")
        put(DPAD_UP, "D-pad ↑"); put(DPAD_DOWN, "D-pad ↓"); put(DPAD_LEFT, "D-pad ←"); put(DPAD_RIGHT, "D-pad →")
        put(TRIGGER_L, "Pad LT"); put(TRIGGER_R, "Pad RT")
    }

    fun name(code: Int): String = when (code) {
        NONE -> "—"
        else -> names[code] ?: "#$code"
    }

    fun isWheel(code: Int) = code == WHEEL_UP || code == WHEEL_DOWN

    /**
     * Fallback for keyboards that don't report a scan code to apps:
     * map common Android key codes to Linux codes.
     */
    fun fromAndroidKeyCode(kc: Int): Int {
        // android.view.KeyEvent constants
        if (kc in 29..54) return "ABCDEFGHIJKLMNOPQRSTUVWXYZ"[kc - 29].let { ch ->
            names.entries.first { it.value == ch.toString() }.key
        }
        if (kc in 7..16) return if (kc == 7) 11 else 2 + (kc - 8)
        if (kc in 131..142) return if (kc <= 140) 59 + (kc - 131) else 87 + (kc - 141)
        return when (kc) {
            62 -> KEY_SPACE; 66 -> KEY_ENTER; 61 -> KEY_TAB; 111 -> KEY_ESC
            59 -> KEY_LEFTSHIFT; 60 -> 54; 113 -> KEY_LEFTCTRL; 114 -> 97
            57 -> KEY_LEFTALT; 58 -> 100; 68 -> KEY_GRAVE; 67 -> 14; 112 -> 111
            19 -> 103; 20 -> 108; 21 -> 105; 22 -> 106
            69 -> 12; 70 -> 13; 71 -> 26; 72 -> 27; 73 -> 43; 74 -> 39; 75 -> 40
            55 -> 51; 56 -> 52; 76 -> 53; 115 -> 58
            else -> NONE
        }
    }
}
