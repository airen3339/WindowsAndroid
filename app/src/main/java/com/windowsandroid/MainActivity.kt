package com.windowsandroid

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.windowsandroid.ui.HomeScreen
import com.windowsandroid.ui.SessionScreen
import com.windowsandroid.ui.WindowsAndroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light icons on the midnight background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        VmController.init(this)
        // The launcher activity is exported: only debug builds take start-up instructions from intents.
        val autostart = BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true
        if (BuildConfig.DEBUG) {
            intent?.getIntExtra(EXTRA_CPUS, 0)?.takeIf { it > 0 }?.let { VmController.store.cpus = it }
            intent?.getLongExtra(EXTRA_RAM_MB, 0)?.takeIf { it > 0 }?.let { VmController.store.ramMb = it }
        }
        if (autostart) VmController.start()

        setContent {
            WindowsAndroidTheme {
                val status by VmController.status.collectAsState()
                var inSession by remember { mutableStateOf(autostart || VmController.isRunning) }
                val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
                    granted[Manifest.permission.RECORD_AUDIO]?.let { VmNative.nativeSetMicAllowed(it) }
                }
                LaunchedEffect(Unit) {
                    val want = buildList {
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        if (VmController.store.sound) add(Manifest.permission.RECORD_AUDIO)
                    }.filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
                    if (want.isNotEmpty() && !autostart) permissions.launch(want.toTypedArray())
                }
                val sessionVisible = inSession && status.phase in setOf(
                    VmStatus.Phase.Starting, VmStatus.Phase.Running, VmStatus.Phase.Exited,
                )
                val external by ExternalScreen.current.collectAsState()
                LaunchedEffect(sessionVisible, external != null) {
                    setSessionMode(sessionVisible, external != null)
                    // Windows on the monitor while the session is open; the phone is its trackpad.
                    ExternalScreen.attach(if (sessionVisible) this@MainActivity else null)
                }
                if (sessionVisible) {
                    SessionScreen(status = status, store = VmController.store, external = external, onLeave = { inSession = false })
                } else {
                    HomeScreen(status = status, store = VmController.store, onStart = {
                        VmController.start()
                        inSession = true
                    }, onResume = { inSession = true })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_AUTOSTART, false)) VmController.start()
    }

    override fun onResume() {
        super.onResume()
        VmController.inForeground = true
        VmNative.nativeSetDisplayPaused(false)
    }

    override fun onPause() {
        VmController.inForeground = false
        // On a monitor Windows stays visible while the phone does something else.
        if (!SurfaceRouter.externalActive) VmNative.nativeSetDisplayPaused(true)
        super.onPause()
    }

    override fun onDestroy() {
        ExternalScreen.attach(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Only the focused app may read the clipboard, so this (not onResume) is the moment.
        if (hasFocus) VmController.pushClipboardToGuest()
    }

    /** Full screen, landscape on a phone, the fastest refresh rate the panel has, sustained clocks.
        With Windows on an external display the phone is only its trackpad: any orientation. */
    private fun setSessionMode(on: Boolean, external: Boolean) {
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                val current = display.mode
                display.supportedModes
                    .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                    .maxByOrNull { it.refreshRate }
                    ?.let { preferredDisplayModeId = it.modeId }
            }
            val large = resources.configuration.smallestScreenWidthDp >= 600 || isDesktopMode() || external
            requestedOrientation = if (large) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            if (getSystemService(PowerManager::class.java).isSustainedPerformanceModeSupported) {
                window.setSustainedPerformanceMode(true)
            }
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window.setSustainedPerformanceMode(false)
        }
    }

    /** Samsung DeX (or any desktop windowing): leave orientation to the window manager. */
    private fun isDesktopMode(): Boolean {
        val cfg = resources.configuration
        return try {
            val f = cfg.javaClass.getField("semDesktopModeEnabled")
            val on = cfg.javaClass.getField("SEM_DESKTOP_MODE_ENABLED").getInt(cfg)
            f.getInt(cfg) == on
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        /** Debug builds: `adb shell am start -n com.windowsandroid/.MainActivity --ez autostart true` boots straight in. */
        const val EXTRA_AUTOSTART = "autostart"
        const val EXTRA_CPUS = "cpus"
        const val EXTRA_RAM_MB = "ram_mb"
    }
}
