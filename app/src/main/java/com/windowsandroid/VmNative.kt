package com.windowsandroid

import android.view.Surface

/**
 * libwinandroid.so: the WindowsAndroid engine (ARM64->ARM64 JIT + virtual PC) and its Android glue.
 * See app/src/main/cpp/jni_bridge.c. One VM per process: the engine keeps its machine in static
 * state, so a VM that has stopped is replaced by restarting the process.
 */
object VmNative {
    init {
        System.loadLibrary("winandroid")
    }

    const val STATE_IDLE = 0
    const val STATE_CREATED = 1
    const val STATE_RUNNING = 2
    const val STATE_EXITED = 3

    @JvmStatic external fun nativeInit(logPath: String)

    /** Returns null on success, or why the VM could not be created. */
    @JvmStatic external fun nativeCreate(
        code: String, vars: String, disk0: String?, disk1: String?,
        shareDir: String?, shareSock: String?,
        cpus: Int, ramMb: Long, net: Boolean, sound: Boolean, statsInterval: Int,
        displayWidth: Int, displayHeight: Int,
        /** ISO images for the two USB DVD drives: Windows' installation disc and the answer disc. */
        dvd0: String?, dvd1: String?,
        /** Press Space for "Press any key to boot from CD or DVD" (installing onto a blank disk). */
        dvdBootKeys: Boolean,
    ): String?

    /** Screen size for Windows' next boot (0 x 0: the default); a restart picks it up. */
    @JvmStatic external fun nativeSetBootResolution(width: Int, height: Int)

    @JvmStatic external fun nativeStart()
    @JvmStatic external fun nativeState(): Int
    @JvmStatic external fun nativeExitReason(): Int
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeFlushDisks()
    @JvmStatic external fun nativePowerButton()

    @JvmStatic external fun nativeSetSurface(surface: Surface?)
    @JvmStatic external fun nativeSetDisplayPaused(paused: Boolean)
    /** (width shl 32) or height of the guest display, or 0 before Windows/UEFI set one up. */
    @JvmStatic external fun nativeDisplaySize(): Long
    @JvmStatic external fun nativeSaveScreenshot(path: String): Boolean

    @JvmStatic external fun nativeKeyHid(usage: Int, down: Boolean)
    @JvmStatic external fun nativeKeyLinux(code: Int, down: Boolean)
    /** x, y in 0..1 over the guest display; buttons: bit0 left, bit1 right, bit2 middle; wheel -127..127. */
    @JvmStatic external fun nativeTablet(x: Double, y: Double, buttons: Int, wheel: Int)
    @JvmStatic external fun nativeCtrlAltDel()
    /** Runs one line of the VM command language (host/vm_commands.h). True for "quit". */
    @JvmStatic external fun nativeCommand(line: String): Boolean
    @JvmStatic external fun nativeStartCommandFile(path: String)

    /** [frames, jitUsed, jitCap, jitFlushes, wfiTicks, kbdReady, tabReady, usbDelivered, netTx, netRx, counterHz] */
    @JvmStatic external fun nativeStats(): LongArray

    /** UTF-8. */
    @JvmStatic external fun nativeTakeOpenUrl(): ByteArray?
    @JvmStatic external fun nativeTakeOAuthDone(): Boolean
    /** True once after Windows reported that installation finished (its first sign-in). */
    @JvmStatic external fun nativeTakeSetupDone(): Boolean
    /** Bytes Windows (or the firmware) has read from DVD drive [index]. */
    @JvmStatic external fun nativeDvdBytesRead(index: Int): Long
    @JvmStatic external fun nativeSetUrlHandlerEnabled(on: Boolean)
    /** UTF-8. */
    @JvmStatic external fun nativeSetClipboard(utf8: ByteArray)
    /** UTF-8. */
    @JvmStatic external fun nativeTakeClipboard(): ByteArray?
    @JvmStatic external fun nativeSetMicAllowed(allowed: Boolean)

    /** setenv() for the engine: HV_* knobs, SLIRP_RESOLV_CONF. Before nativeCreate. */
    @JvmStatic external fun nativeSetEnv(name: String, value: String)

    /** Blocking. Expands a .hvsparse container into a sparse raw image. */
    @JvmStatic external fun nativeUnpackSparse(src: String, dst: String): Boolean
    @JvmStatic external fun nativeUnpackProgress(): Double
}
