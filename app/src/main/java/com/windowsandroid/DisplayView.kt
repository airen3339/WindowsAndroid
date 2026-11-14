package com.windowsandroid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The Windows screen: a SurfaceView the native GLES presenter draws into, and every way of
 * pointing and typing a phone has, turned into the guest's USB keyboard and absolute tablet.
 *
 *  - Finger, trackpad mode (default on a phone): the screen is a laptop trackpad. Drag moves the
 *    cursor, tap clicks, tap-then-drag drags, two-finger tap right-clicks, two-finger drag scrolls,
 *    long press right-clicks.
 *  - Finger, touch mode: taps and drags land where the finger is.
 *  - S Pen / stylus: always direct, with hover moving the cursor and the side button right-clicking.
 *  - Mouse (Bluetooth, USB, DeX): absolute, all three buttons and the wheel; Android's own pointer
 *    is hidden because Windows draws its cursor.
 *  - Hardware keyboard: Linux scan codes straight through; IME text becomes key presses.
 *
 * Anything held down (keys, buttons) is released when the view loses focus, so an Alt+Tab to
 * another app or a pull of the notification shade never leaves a key stuck in Windows.
 *
 * With an external display attached ([ExternalScreen]) there are two of these: the one on the
 * monitor shows Windows, and the phone's stays a trackpad and keyboard whatever its mode.
 */
@SuppressLint("ViewConstructor")
class DisplayView(
    context: Context,
    private val role: SurfaceRouter.Role = SurfaceRouter.Role.Phone,
) : SurfaceView(context), SurfaceHolder.Callback {
    var trackpadMode = true
    var onUserInteraction: (() -> Unit)? = null

    // Cursor position in guest coordinates (0..1), as the tablet last reported it.
    private var cx = 0.5
    private var cy = 0.5
    private var buttons = 0
    private val heldScanCodes = HashSet<Int>()
    private val density = resources.displayMetrics.density

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { requestFocus() } // in the factory it is not attached yet, so focus would not stick
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Trackpad strokes start anywhere, edges included: keep the back gesture off the side
        // strips (Android grants up to 200 dp of exclusion per edge).
        val strip = (48 * density).toInt()
        val h = (bottom - top).coerceAtMost((200 * density).toInt())
        val y = (bottom - top - h) / 2
        systemGestureExclusionRects = listOf(Rect(0, y, strip, y + h), Rect(right - left - strip, y, right - left, y + h))
    }

    // ---- surface ----

    override fun surfaceCreated(holder: SurfaceHolder) = SurfaceRouter.set(role, holder.surface)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
        SurfaceRouter.set(role, holder.surface)
    override fun surfaceDestroyed(holder: SurfaceHolder) = SurfaceRouter.set(role, null)

    /** Windows is on another screen: fingers here can only be a trackpad. */
    private val remote get() = role == SurfaceRouter.Role.Phone && SurfaceRouter.externalActive

    // ---- focus: let go of everything held ----

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) releaseAll()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) releaseAll()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    fun releaseAll() {
        heldScanCodes.forEach { InputPump.keyLinux(it, false) }
        heldScanCodes.clear()
        removeCallbacks(longPress)
        removeCallbacks(directPress)
        if (buttons != 0) { buttons = 0; send() }
        twoFinger = false
        dragging = false
    }

    // ---- geometry: the same aspect fit the presenter draws with (display_gles.c) ----

    private data class Fit(val left: Float, val top: Float, val width: Float, val height: Float)

    private fun fit(): Fit {
        val size = VmNative.nativeDisplaySize()
        val fw = (size ushr 32).toFloat()
        val fh = (size and 0xffffffffL).toFloat()
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        if (fw <= 0f || fh <= 0f) return Fit(0f, 0f, vw, vh)
        val dst = vw / vh
        val src = fw / fh
        return if (dst > src) {
            val w = vw * src / dst
            Fit((vw - w) / 2, 0f, w, vh)
        } else {
            val h = vh * dst / src
            Fit(0f, (vh - h) / 2, vw, h)
        }
    }

    private fun moveTo(x: Float, y: Float) {
        val f = fit()
        cx = ((x - f.left) / f.width).toDouble().coerceIn(0.0, 1.0)
        cy = ((y - f.top) / f.height).toDouble().coerceIn(0.0, 1.0)
    }

    private fun send(wheel: Int = 0) = VmNative.nativeTablet(cx, cy, buttons, wheel)

    /** Press and release a button; the release goes out a moment later so Windows sees both. */
    private fun click(button: Int) {
        buttons = buttons or button; send()
        postDelayed({ buttons = buttons and button.inv(); send() }, 40)
    }

    // ---- touch, stylus, mouse ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        onUserInteraction?.invoke()
        if (!hasFocus()) requestFocus()
        val tool = e.getToolType(0)
        return when {
            tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER -> stylus(e)
            tool == MotionEvent.TOOL_TYPE_MOUSE || e.isFromSource(InputDevice.SOURCE_MOUSE) -> mouse(e)
            trackpadMode || remote -> trackpad(e)
            else -> direct(e)
        }
    }

    private var wheelAcc = 0f

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        val isMouse = e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE || e.isFromSource(InputDevice.SOURCE_MOUSE)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                moveTo(e.x, e.y)
                buttons = if (isMouse) mouseButtons(e) else 0
                send()
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (buttons != 0) { buttons = 0; send() }
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                // Smooth-scrolling touchpads report fractions of a notch: add them up.
                wheelAcc += e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val ticks = wheelAcc.toInt()
                if (ticks != 0) {
                    wheelAcc -= ticks
                    send(wheel = ticks.coerceIn(-127, 127))
                }
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (!isMouse) return true // the S Pen's side button is read with its touches
                buttons = mouseButtons(e); send(); return true
            }
        }
        return super.onGenericMotionEvent(e)
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon =
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL) // Windows draws the cursor

    private fun mouseButtons(e: MotionEvent): Int {
        val b = e.buttonState
        return (if (b and MotionEvent.BUTTON_PRIMARY != 0) 1 else 0) or
            (if (b and MotionEvent.BUTTON_SECONDARY != 0) 2 else 0) or
            (if (b and MotionEvent.BUTTON_TERTIARY != 0) 4 else 0)
    }

    private fun mouse(e: MotionEvent): Boolean {
        moveTo(e.x, e.y)
        buttons = when (e.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 0
            // A touchpad tap arrives as a touch with no button: that is a left click.
            else -> mouseButtons(e).let { if (it == 0) 1 else it }
        }
        send()
        return true
    }

    private fun stylus(e: MotionEvent): Boolean {
        moveTo(e.x, e.y)
        val side = e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> buttons = if (side) 2 else 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> buttons = 0
            else -> {} // a palm or a second contact landing mid-stroke changes nothing
        }
        send()
        return true
    }

    // ---- fingers ----

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var twoFinger = false
    private var twoFingerMoved = false
    private var lastScrollY = 0f
    private var scrollAcc = 0f
    private var lastTapUp = 0L
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPress = Runnable {
        if (!moved && !twoFinger) { click(2); moved = true }
    }
    // Touch mode presses the left button only once the finger is clearly not starting a
    // two-finger gesture (right-click, scroll): after the tap timeout, or when it moves.
    private val directPress = Runnable {
        if (!twoFinger && buttons and 1 == 0) { buttons = buttons or 1; send() }
    }

    private fun gestureStart(e: MotionEvent) {
        downTime = SystemClock.uptimeMillis()
        downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
        moved = false
        twoFinger = false
        twoFingerMoved = false
    }

    /** Average y of the fingers still down (the lifting one excluded on POINTER_UP). */
    private fun avgY(e: MotionEvent): Float {
        val lifting = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        var s = 0f
        var n = 0
        for (i in 0 until e.pointerCount) if (i != lifting) { s += e.getY(i); n++ }
        return if (n > 0) s / n else e.y
    }

    private fun startScroll(e: MotionEvent) {
        twoFinger = true; twoFingerMoved = false; lastScrollY = avgY(e); scrollAcc = 0f
    }

    private fun scroll(e: MotionEvent) {
        val y = avgY(e)
        scrollAcc += y - lastScrollY
        lastScrollY = y
        val step = 24 * density
        if (abs(scrollAcc) >= step) {
            twoFingerMoved = true
            val ticks = (scrollAcc / step).toInt()
            scrollAcc -= ticks * step
            send(wheel = ticks.coerceIn(-127, 127)) // fingers down = content down = wheel up
        }
    }

    private fun direct(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStart(e)
                moveTo(e.x, e.y)
                buttons = 0; send()
                postDelayed(directPress, ViewConfiguration.getTapTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(directPress)
                if (buttons and 1 != 0) { buttons = 0; send() }
                startScroll(e)
            }
            MotionEvent.ACTION_POINTER_UP -> lastScrollY = avgY(e)
            MotionEvent.ACTION_MOVE -> {
                if (twoFinger) scroll(e)
                else {
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                        moved = true
                        removeCallbacks(directPress)
                        directPress.run()
                    }
                    moveTo(e.x, e.y); send()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(directPress)
                val quick = SystemClock.uptimeMillis() - downTime < 350
                when {
                    twoFinger -> if (!twoFingerMoved && quick) click(2)
                    buttons and 1 != 0 -> { buttons = 0; send() }
                    else -> click(1) // lifted before the press went out: a tap
                }
                twoFinger = false
            }
            MotionEvent.ACTION_CANCEL -> releaseAll() // a system gesture took the touch: no click
        }
        return true
    }

    private fun trackpad(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStart(e)
                // Tap, then touch again quickly: drag with the left button held.
                dragging = downTime - lastTapUp < 280
                if (dragging) { buttons = 1; send() }
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                startScroll(e)
            }
            MotionEvent.ACTION_POINTER_UP -> lastScrollY = avgY(e)
            MotionEvent.ACTION_MOVE -> {
                if (twoFinger) { scroll(e); return true }
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                if (!moved && hypot(e.x - downX, e.y - downY) > slop) { moved = true; removeCallbacks(longPress) }
                if (moved) {
                    val f = fit()
                    // Pointer acceleration: slow moves are precise, fast flicks cross the screen.
                    val speed = hypot(dx, dy).toDouble()
                    val gain = (1.0 + (speed / (8 * density)).coerceAtMost(2.5)) * 1.1
                    cx = (cx + dx * gain / f.width).coerceIn(0.0, 1.0)
                    cy = (cy + dy * gain / f.height).coerceIn(0.0, 1.0)
                    send()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val quick = SystemClock.uptimeMillis() - downTime < 250
                when {
                    twoFinger -> if (!twoFingerMoved && quick) click(2)
                    dragging -> { buttons = 0; send(); lastTapUp = 0 }
                    !moved && quick -> { click(1); lastTapUp = SystemClock.uptimeMillis() }
                }
                dragging = false
                twoFinger = false
            }
            MotionEvent.ACTION_CANCEL -> releaseAll() // a system gesture took the touch: no click
        }
        return true
    }

    // ---- keyboard ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // System keys stay with Android.
        if (code == KeyEvent.KEYCODE_BACK && event.device?.isVirtual != false) return super.dispatchKeyEvent(event)
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN ||
            code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_APP_SWITCH
        ) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return true
        val down = event.action == KeyEvent.ACTION_DOWN
        val physical = event.device?.isVirtual == false && event.scanCode in 1..255
        if (physical) {
            // A real keyboard: its Linux scan code is exactly what the engine's map wants,
            // whatever the layout. Auto-repeat is the guest's business (HID has none).
            if (down && event.repeatCount > 0) return true
            if (down) heldScanCodes.add(event.scanCode) else heldScanCodes.remove(event.scanCode)
            InputPump.keyLinux(event.scanCode, down)
        } else {
            val usage = HidKeys.fromKeyCode(code) ?: return super.dispatchKeyEvent(event)
            if (down) {
                if (event.isShiftPressed) InputPump.tap(usage, HidKeys.LSHIFT) else InputPump.tap(usage)
            }
        }
        onUserInteraction?.invoke()
        return true
    }

    // ---- soft keyboard (IME) ----

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // A "visible password" field: no suggestions, no autocorrect, no composing text, so every
        // keystroke arrives as soon as it is typed.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            // What the guest has received of the current composing text: only characters that
            // have a key are typed, so only those may be taken back with Backspace.
            private var composingSent = ""

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                setComposing("")
                composingSent = ""
                InputPump.typeText(text)
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                setComposing(text.toString()); return true
            }

            override fun finishComposingText(): Boolean { composingSent = ""; return true }

            private fun setComposing(new: String) {
                val typed = new.filter { HidKeys.forChar(it) != null }
                val common = composingSent.commonPrefixWith(typed).length
                InputPump.backspace(composingSent.length - common)
                InputPump.typeText(typed.substring(common))
                composingSent = typed
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                InputPump.backspace(beforeLength)
                repeat(afterLength) { InputPump.tap(HidKeys.DELETE) }
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
                deleteSurroundingText(beforeLength, afterLength)

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) HidKeys.fromKeyCode(event.keyCode)?.let {
                    if (event.isShiftPressed) InputPump.tap(it, HidKeys.LSHIFT) else InputPump.tap(it)
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                InputPump.tap(HidKeys.ENTER); return true
            }
        }
    }

    fun showKeyboard(show: Boolean) {
        if (show) {
            requestFocus()
            windowInsetsController?.show(WindowInsets.Type.ime())
        } else {
            windowInsetsController?.hide(WindowInsets.Type.ime())
        }
    }
}
