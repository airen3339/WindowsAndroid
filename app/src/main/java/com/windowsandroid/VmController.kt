package com.windowsandroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import android.widget.Toast
import com.windowsandroid.install.WindowsInstall
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the UI shows about the machine. */
data class VmStatus(
    val phase: Phase = Phase.Idle,
    val message: String = "",
    val unpackProgress: Double = 0.0,
    val fbWidth: Int = 0,
    val fbHeight: Int = 0,
    val fps: Double = 0.0,
    val jitUsedMb: Double = 0.0,
    val jitCapMb: Double = 0.0,
    val jitFlushes: Long = 0,
    val idlePercent: Double = 0.0,
    val keyboardReady: Boolean = false,
    val tabletReady: Boolean = false,
    val netFrames: Long = 0,
    val counterMhz: Double = 0.0,
    /** Windows Setup is running from the ISO (until Windows reports it is installed). */
    val installing: Boolean = false,
) {
    enum class Phase { Idle, Unpacking, Starting, Running, Exited, Failed }
}

/**
 * Process-wide owner of the virtual machine. There is one engine per process (it keeps its machine
 * in static state), so this is a singleton, and once Windows has shut down the process is restarted
 * rather than the engine reused.
 */
object VmController {
    private const val TAG = "WinVM"
    private const val MAX_CLIP_BYTES = 256 * 1024
    // A failure in background housekeeping must never take the process (and Windows) down.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "background task failed", t) }
    )
    private val _status = MutableStateFlow(VmStatus())
    val status: StateFlow<VmStatus> = _status.asStateFlow()

    private lateinit var app: Context
    lateinit var store: MachineStore
        private set
    private var pollStarted = false
    @Volatile private var lastClipFromGuest: String? = null
    /** Whether a WindowsAndroid activity is resumed (links can open directly) or not (notify). */
    @Volatile var inForeground = false
    /** The Windows ISO, open for the engine for as long as the process lives. */
    private var installIso: ParcelFileDescriptor? = null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        store = MachineStore(app)
        VmNative.nativeInit(store.logFile.absolutePath)
        ExternalScreen.init(app)
    }

    val isRunning get() = _status.value.phase == VmStatus.Phase.Running

    /** Unpack the disk if needed, create the machine, and start it. Safe to call twice. */
    fun start() {
        // Claim the start atomically: a double tap (or autostart plus a tap) must not boot twice.
        while (true) {
            val cur = _status.value
            if (cur.phase != VmStatus.Phase.Idle && cur.phase != VmStatus.Phase.Failed) return
            if (cur.phase == VmStatus.Phase.Failed && VmNative.nativeState() != VmNative.STATE_IDLE) return
            if (_status.compareAndSet(cur, cur.copy(phase = VmStatus.Phase.Starting, message = "Starting…"))) break
        }
        // Foreground priority before any long work (unpacking a disk takes minutes), while the
        // UI is still in front: Android refuses to start a foreground service from the background.
        try {
            ContextCompat.startForegroundService(app, Intent(app, VmService::class.java))
        } catch (t: Throwable) {
            Log.w(TAG, "could not start the foreground service", t)
        }
        scope.launch {
            try {
                bootMachine()
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                if (VmNative.nativeState() >= VmNative.STATE_RUNNING) return@launch // Windows is up; keep it
                _status.update { it.copy(phase = VmStatus.Phase.Failed, message = t.message ?: t.toString()) }
                app.stopService(Intent(app, VmService::class.java))
            }
        }
    }

    private suspend fun bootMachine() {
        val s = store
        if (!s.hasFirmware) throw IllegalStateException("UEFI firmware (code.fd) is missing: see Machine files.")
        if (!s.hasVars) s.createBlankVars()
        if (s.needsUnpack) {
            _status.update { it.copy(phase = VmStatus.Phase.Unpacking, message = "Unpacking windows11.hvsparse…") }
            val progress = scope.launch {
                while (isActive) {
                    _status.update { it.copy(unpackProgress = VmNative.nativeUnpackProgress()) }
                    delay(250)
                }
            }
            val tmp = java.io.File(s.root, "windows11.img.unpacking")
            val ok = withContext(Dispatchers.IO) { VmNative.nativeUnpackSparse(s.sparseDisk.absolutePath, tmp.absolutePath) }
            progress.cancel()
            if (!ok || !tmp.renameTo(s.rawDisk)) throw IllegalStateException("Could not unpack windows11.hvsparse (is there enough free space?)")
            s.sparseDisk.delete() // the raw image replaces it; keeping both doubles the space used
        }
        _status.update { it.copy(phase = VmStatus.Phase.Starting, message = "Starting…") }
        // Installing from an ISO: Microsoft's disc and the app's answer disc in two USB DVD drives.
        var dvd0: String? = null
        var dvd1: String? = null
        var bootKeys = false
        val installing = s.installing
        if (installing) {
            val uri = Uri.parse(s.installIso)
            val info = when (val c = withContext(Dispatchers.IO) { WindowsInstall.check(app, uri) }) {
                is WindowsInstall.Checked.Ok -> c.info
                is WindowsInstall.Checked.Bad -> throw IllegalStateException(c.why)
            }
            withContext(Dispatchers.IO) {
                if (!s.hasDisk) s.createBlankDisk(WindowsInstall.DISK_BYTES)
                bootKeys = s.diskIsBlank()
                WindowsInstall.writeAnswerDisc(app, s, info.language)
            }
            val pfd = WindowsInstall.open(app, uri)
            installIso = pfd
            dvd0 = "/proc/self/fd/${pfd.fd}"
            dvd1 = s.answerDisc.absolutePath
            Log.i(TAG, "installing Windows from ${info.label} (${info.language}), blank disk: $bootKeys")
        }
        // Setup reports back over the share at 10.0.2.4, so an installation needs both.
        val share = s.shareFolder || installing
        val net = s.network || installing
        s.shareSocket.delete()
        if (net) followHostDns()
        // A monitor attached now: boot at its size (Windows keeps the boot-time screen size).
        val (bootW, bootH) = ExternalScreen.bootResolution()
        val err = VmNative.nativeCreate(
            code = s.firmware().absolutePath,
            vars = s.vars.absolutePath,
            disk0 = s.rawDisk.takeIf { it.isFile }?.absolutePath,
            disk1 = s.installer.takeIf { it.isFile }?.absolutePath,
            shareDir = if (share) s.shareDir.absolutePath else null,
            shareSock = if (share) s.shareSocket.absolutePath else null,
            cpus = s.cpus,
            ramMb = s.ramMb,
            net = net,
            sound = s.sound,
            statsInterval = 0,
            displayWidth = bootW,
            displayHeight = bootH,
            dvd0 = dvd0,
            dvd1 = dvd1,
            dvdBootKeys = bootKeys,
        )
        if (err != null) throw IllegalStateException(err)
        VmNative.nativeSetUrlHandlerEnabled(s.openLinksOnPhone)
        VmNative.nativeSetMicAllowed(
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
        VmNative.nativeStart()
        // Automation (adb-driven testing) exists in debug builds only.
        if (BuildConfig.DEBUG) VmNative.nativeStartCommandFile(store.commandFile.absolutePath)
        _status.update { it.copy(phase = VmStatus.Phase.Running, message = "Running", installing = installing) }
        startPolling()
    }

    private fun startPolling() {
        if (pollStarted) return
        pollStarted = true
        scope.launch {
            var lastFrames = 0L
            var lastWfi = 0L
            var lastT = System.nanoTime()
            while (isActive) {
                delay(500)
                val st = VmNative.nativeStats()
                val now = System.nanoTime()
                val dt = (now - lastT) / 1e9
                val hz = st[10].toDouble()
                val size = VmNative.nativeDisplaySize()
                val wfiTicks = st[4] - lastWfi
                _status.update {
                    it.copy(
                        fbWidth = (size ushr 32).toInt(),
                        fbHeight = (size and 0xffffffffL).toInt(),
                        fps = (st[0] - lastFrames) / dt,
                        jitUsedMb = st[1] / 1048576.0,
                        jitCapMb = st[2] / 1048576.0,
                        jitFlushes = st[3],
                        idlePercent = if (hz > 0) (100.0 * wfiTicks / hz / dt / store.cpus).coerceIn(0.0, 100.0) else 0.0,
                        keyboardReady = st[5] != 0L,
                        tabletReady = st[6] != 0L,
                        netFrames = st[8] + st[9],
                        counterMhz = hz / 1e6,
                    )
                }
                lastFrames = st[0]; lastWfi = st[4]; lastT = now
                pumpHostIntegration()
                if (_status.value.installing && VmNative.nativeTakeSetupDone()) {
                    // The first sign-in of the new Windows: the discs are no longer needed.
                    WindowsInstall.finish(app, store)
                    _status.update { it.copy(installing = false) }
                    try { // back to the ordinary notification
                        ContextCompat.startForegroundService(app, Intent(app, VmService::class.java))
                    } catch (t: Throwable) {
                        Log.w(TAG, "could not update the notification", t)
                    }
                    Log.i(TAG, "Windows is installed")
                    withContext(Dispatchers.Main) { Toast.makeText(app, "Windows is installed", Toast.LENGTH_LONG).show() }
                }
                if (VmNative.nativeState() == VmNative.STATE_EXITED && _status.value.phase == VmStatus.Phase.Running) {
                    _status.update { it.copy(phase = VmStatus.Phase.Exited, message = "Windows has shut down.") }
                    app.stopService(Intent(app, VmService::class.java))
                }
            }
        }
    }

    /** Links Windows asked the phone to open (OAuth sign-in), and the clipboard in both directions. */
    private suspend fun pumpHostIntegration() {
        try {
            VmNative.nativeTakeOpenUrl()?.let { bytes ->
                val url = String(bytes, Charsets.UTF_8)
                withContext(Dispatchers.Main) { openOnPhone(url) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not open a link from Windows", t)
        }
        try {
            VmNative.nativeTakeClipboard()?.let { bytes ->
                if (bytes.size > MAX_CLIP_BYTES) return@let // a binder transaction cannot carry it
                val text = String(bytes, Charsets.UTF_8)
                lastClipFromGuest = text
                withContext(Dispatchers.Main) {
                    app.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Windows", text))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not copy Windows' clipboard", t)
        }
    }

    /** Hand the phone's clipboard to Windows. Call it once the window has focus: Android only
        lets the focused app read the clipboard. */
    fun pushClipboardToGuest() {
        if (!isRunning) return
        try {
            val cm = app.getSystemService(ClipboardManager::class.java)
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString() ?: return
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (text != lastClipFromGuest && bytes.size <= MAX_CLIP_BYTES) {
                lastClipFromGuest = text
                VmNative.nativeSetClipboard(bytes)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not read the clipboard", t)
        }
    }

    private fun openOnPhone(url: String) {
        Log.i(TAG, "guest asked to open $url")
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return
        if (!inForeground) {
            // Apps in the background may not start activities, even with a foreground service:
            // hand the link to the user as a notification instead of dropping it.
            VmService.notifyLink(app, uri)
            return
        }
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().apply {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.launchUrl(app, uri)
        } catch (_: Throwable) {
            try {
                app.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (t: Throwable) {
                Log.w(TAG, "no browser for $url", t)
            }
        }
    }

    /**
     * The guest's DNS server (10.0.2.3) forwards to the phone's. libslirp finds those in a
     * resolv.conf, which Android does not have: keep one in the app's files, rewritten whenever
     * the default network (Wi-Fi, mobile data, VPN) changes; libslirp re-reads it when it changes.
     */
    private fun followHostDns() {
        val file = java.io.File(app.filesDir, "resolv.conf")
        val cm = app.getSystemService(ConnectivityManager::class.java)
        fun write(lp: LinkProperties?) {
            val servers = lp?.dnsServers.orEmpty().mapNotNull { it.hostAddress?.substringBefore('%') }
                .ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
            try {
                val tmp = java.io.File(app.filesDir, "resolv.conf.tmp")
                tmp.writeText(servers.joinToString("") { "nameserver $it\n" })
                tmp.renameTo(file)
            } catch (t: Throwable) {
                Log.w(TAG, "could not write resolv.conf", t)
            }
        }
        write(cm.activeNetwork?.let { cm.getLinkProperties(it) })
        VmNative.nativeSetEnv("SLIRP_RESOLV_CONF", file.absolutePath)
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = write(lp)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "could not follow network changes", t)
        }
    }

    fun setOpenLinksOnPhone(on: Boolean) {
        store.openLinksOnPhone = on
        if (isRunning) VmNative.nativeSetUrlHandlerEnabled(on)
    }

    /** ACPI power button: Windows shuts down cleanly (or asks, depending on its power settings). */
    fun shutDown() {
        if (isRunning) VmNative.nativePowerButton()
    }

    /** Pull the plug: stop the CPUs, flush the disk (which can take a while) and end the process.
        Never on the main thread. */
    fun forceStop() {
        scope.launch(Dispatchers.IO) {
            VmNative.nativeStop() // stops the vCPUs and flushes the disk
            app.stopService(Intent(app, VmService::class.java))
            Process.killProcess(Process.myPid())
        }
    }

    /** After Windows has shut down: the engine is single-use, so the process goes. */
    fun exitProcess() {
        scope.launch(Dispatchers.IO) {
            VmNative.nativeFlushDisks()
            app.stopService(Intent(app, VmService::class.java))
            Process.killProcess(Process.myPid())
        }
    }

    fun screenshot(): java.io.File? {
        val f = java.io.File(app.getExternalFilesDir(null), "screenshot-${System.currentTimeMillis()}.png")
        return if (VmNative.nativeSaveScreenshot(f.absolutePath)) f else null
    }
}
