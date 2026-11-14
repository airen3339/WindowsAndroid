package com.windowsandroid

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone as a portable desktop: plug it into a monitor or TV (USB-C DisplayPort, HDMI
 * adapter, a dock, or a wireless display) and Windows moves there, full screen, while the
 * phone's own screen turns into the trackpad and keyboard.
 *
 * Android shows a second display as a copy of the phone until an app puts a [Presentation] on
 * it; this does that for the duration of a session. Windows' screen size is fixed at boot (the
 * firmware's display mode), so a machine started with a monitor attached boots at the monitor's
 * size, and plugging one in later sets that size for Windows' next restart.
 *
 * In Samsung DeX or Android's desktop mode the app itself runs on the monitor; there is no
 * second display to present on and the session simply fills its window there.
 */
object ExternalScreen {
    private const val TAG = "WindowsAndroid"
    /** Above this Windows draws too many pixels in software to stay smooth: halve until it fits. */
    private const val MAX_W = 2560
    private const val MAX_H = 1600

    data class Info(val displayId: Int, val name: String, val width: Int, val height: Int, val bootWidth: Int, val bootHeight: Int)

    private val _current = MutableStateFlow<Info?>(null)
    /** The display Windows is (or would be) shown on, besides the phone's; null when there is none. */
    val current: StateFlow<Info?> = _current.asStateFlow()

    private lateinit var dm: DisplayManager
    private val main = Handler(Looper.getMainLooper())
    private var presentation: WindowsPresentation? = null
    private var host: Context? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    fun init(context: Context) {
        if (::dm.isInitialized) return
        dm = context.applicationContext.getSystemService(DisplayManager::class.java)
        dm.registerDisplayListener(listener, main)
        refresh()
    }

    /** The resolution to boot Windows at: the external display's, or 0 x 0 (the default, 800 x 600). */
    fun bootResolution(): Pair<Int, Int> = _current.value?.let { it.bootWidth to it.bootHeight } ?: (0 to 0)

    /** Show Windows on the external display while [context] (the session's activity) is up; null stops. */
    fun attach(context: Context?) {
        host = context
        refresh()
    }

    private fun target(): Display? {
        val own = (host as? android.app.Activity)?.display?.displayId ?: Display.DEFAULT_DISPLAY
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.displayId != own && it.state != Display.STATE_OFF }
    }

    private fun refresh() {
        val d = target()
        val info = d?.let {
            val mode = it.mode
            var w = mode.physicalWidth
            var h = mode.physicalHeight
            while (w > MAX_W || h > MAX_H) { w /= 2; h /= 2 }
            Info(it.displayId, it.name ?: "External display", mode.physicalWidth, mode.physicalHeight, w and 1.inv(), h and 1.inv())
        }
        if (info != _current.value) {
            _current.value = info
            Log.i(TAG, "external display: $info")
            // Windows reads the size at boot: a restart picks up the new screen.
            if (VmNative.nativeState() >= VmNative.STATE_CREATED) {
                val (w, h) = bootResolution()
                VmNative.nativeSetBootResolution(w, h)
            }
        }
        val ctx = host
        val shown = presentation
        if (ctx == null || d == null) {
            if (shown != null) { presentation = null; shown.dismissSafely() }
            return
        }
        if (shown != null && shown.display.displayId == d.displayId) return
        shown?.dismissSafely()
        presentation = WindowsPresentation(ctx, d).also {
            try {
                it.show()
            } catch (t: WindowManager.InvalidDisplayException) {
                Log.w(TAG, "could not present on ${d.name}", t)
                presentation = null
            }
        }
    }

    private fun Presentation.dismissSafely() = try { dismiss() } catch (_: Throwable) {}

    /** Full screen Windows on the second display. Touchscreen monitors get direct touch. */
    private class WindowsPresentation(outer: Context, display: Display) : Presentation(outer, display) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(DisplayView(context, SurfaceRouter.Role.External).apply { trackpadMode = false })
        }
    }
}

/**
 * One presenter, two places it can draw: the phone's DisplayView and the external display's.
 * The external one wins while it exists.
 */
object SurfaceRouter {
    enum class Role { Phone, External }

    private var phone: Surface? = null
    private var external: Surface? = null
    private var active: Surface? = null

    val externalActive: Boolean @Synchronized get() = external != null

    @Synchronized
    fun set(role: Role, surface: Surface?) {
        if (role == Role.Phone) phone = surface else external = surface
        val want = (external ?: phone)?.takeIf { it.isValid }
        if (want !== active) {
            // Let the render thread let go of the old window before it can disappear.
            if (active != null) VmNative.nativeSetSurface(null)
            active = want
            if (want != null) VmNative.nativeSetSurface(want)
        } else if (want != null && want === surface) {
            VmNative.nativeSetSurface(want) // the same window, resized
        }
    }
}
