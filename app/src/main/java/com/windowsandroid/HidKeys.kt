package com.windowsandroid

import android.view.KeyEvent

/** USB HID keyboard usages (HID Usage Tables, page 0x07) for what Android hands us. */
object HidKeys {
    const val LCTRL = 0xe0
    const val LSHIFT = 0xe1
    const val LALT = 0xe2
    const val LGUI = 0xe3 // the Windows key
    const val RCTRL = 0xe4
    const val RSHIFT = 0xe5
    const val RALT = 0xe6
    const val RGUI = 0xe7
    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2a
    const val TAB = 0x2b
    const val SPACE = 0x2c
    const val DELETE = 0x4c
    const val RIGHT = 0x4f
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val F1 = 0x3a
    const val HOME = 0x4a
    const val END = 0x4d
    const val PAGE_UP = 0x4b
    const val PAGE_DOWN = 0x4e
    const val PRINT_SCREEN = 0x46
    const val MENU = 0x65 // context-menu key

    private val keycodeToHid: Map<Int, Int> = buildMap {
        for (i in 0 until 26) put(KeyEvent.KEYCODE_A + i, 0x04 + i)
        for (i in 1..9) put(KeyEvent.KEYCODE_1 + i - 1, 0x1e + i - 1)
        put(KeyEvent.KEYCODE_0, 0x27)
        put(KeyEvent.KEYCODE_ENTER, ENTER)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, 0x58)
        put(KeyEvent.KEYCODE_ESCAPE, ESC)
        put(KeyEvent.KEYCODE_DEL, BACKSPACE)
        put(KeyEvent.KEYCODE_FORWARD_DEL, DELETE)
        put(KeyEvent.KEYCODE_TAB, TAB)
        put(KeyEvent.KEYCODE_SPACE, SPACE)
        put(KeyEvent.KEYCODE_MINUS, 0x2d)
        put(KeyEvent.KEYCODE_EQUALS, 0x2e)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 0x2f)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 0x30)
        put(KeyEvent.KEYCODE_BACKSLASH, 0x31)
        put(KeyEvent.KEYCODE_SEMICOLON, 0x33)
        put(KeyEvent.KEYCODE_APOSTROPHE, 0x34)
        put(KeyEvent.KEYCODE_GRAVE, 0x35)
        put(KeyEvent.KEYCODE_COMMA, 0x36)
        put(KeyEvent.KEYCODE_PERIOD, 0x37)
        put(KeyEvent.KEYCODE_SLASH, 0x38)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 0x39)
        for (i in 0 until 12) put(KeyEvent.KEYCODE_F1 + i, F1 + i)
        put(KeyEvent.KEYCODE_SYSRQ, PRINT_SCREEN)
        put(KeyEvent.KEYCODE_SCROLL_LOCK, 0x47)
        put(KeyEvent.KEYCODE_BREAK, 0x48)
        put(KeyEvent.KEYCODE_INSERT, 0x49)
        put(KeyEvent.KEYCODE_MOVE_HOME, HOME)
        put(KeyEvent.KEYCODE_PAGE_UP, PAGE_UP)
        put(KeyEvent.KEYCODE_MOVE_END, END)
        put(KeyEvent.KEYCODE_PAGE_DOWN, PAGE_DOWN)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, RIGHT)
        put(KeyEvent.KEYCODE_DPAD_LEFT, LEFT)
        put(KeyEvent.KEYCODE_DPAD_DOWN, DOWN)
        put(KeyEvent.KEYCODE_DPAD_UP, UP)
        put(KeyEvent.KEYCODE_NUM_LOCK, 0x53)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 0x54)
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 0x55)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 0x56)
        put(KeyEvent.KEYCODE_NUMPAD_ADD, 0x57)
        for (i in 1..9) put(KeyEvent.KEYCODE_NUMPAD_1 + i - 1, 0x59 + i - 1)
        put(KeyEvent.KEYCODE_NUMPAD_0, 0x62)
        put(KeyEvent.KEYCODE_NUMPAD_DOT, 0x63)
        put(KeyEvent.KEYCODE_MENU, MENU)
        put(KeyEvent.KEYCODE_CTRL_LEFT, LCTRL)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, LSHIFT)
        put(KeyEvent.KEYCODE_ALT_LEFT, LALT)
        put(KeyEvent.KEYCODE_META_LEFT, LGUI)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, RCTRL)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, RSHIFT)
        put(KeyEvent.KEYCODE_ALT_RIGHT, RALT)
        put(KeyEvent.KEYCODE_META_RIGHT, RGUI)
        put(KeyEvent.KEYCODE_VOLUME_MUTE, 0x7f)
    }

    fun fromKeyCode(keyCode: Int): Int? = keycodeToHid[keyCode]

    /** A character on a US layout: (usage, needs shift), or null if there is no such key. */
    fun forChar(c: Char): Pair<Int, Boolean>? = when (c) {
        in 'a'..'z' -> (0x04 + (c - 'a')) to false
        in 'A'..'Z' -> (0x04 + (c - 'A')) to true
        in '1'..'9' -> (0x1e + (c - '1')) to false
        '0' -> 0x27 to false
        ' ' -> SPACE to false
        '\n', '\r' -> ENTER to false
        '\t' -> TAB to false
        '-' -> 0x2d to false; '_' -> 0x2d to true
        '=' -> 0x2e to false; '+' -> 0x2e to true
        '[' -> 0x2f to false; '{' -> 0x2f to true
        ']' -> 0x30 to false; '}' -> 0x30 to true
        '\\' -> 0x31 to false; '|' -> 0x31 to true
        ';' -> 0x33 to false; ':' -> 0x33 to true
        '\'' -> 0x34 to false; '"' -> 0x34 to true
        '`' -> 0x35 to false; '~' -> 0x35 to true
        ',' -> 0x36 to false; '<' -> 0x36 to true
        '.' -> 0x37 to false; '>' -> 0x37 to true
        '/' -> 0x38 to false; '?' -> 0x38 to true
        '!' -> 0x1e to true; '@' -> 0x1f to true; '#' -> 0x20 to true; '$' -> 0x21 to true
        '%' -> 0x22 to true; '^' -> 0x23 to true; '&' -> 0x24 to true; '*' -> 0x25 to true
        '(' -> 0x26 to true; ')' -> 0x27 to true
        else -> null
    }
}
