package com.windowsandroid

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Where the machine lives and how it is configured.
 *
 * Files sit in the app's external files dir so they can be copied in with adb:
 *   /sdcard/Android/data/com.windowsandroid/files/Machines/Win11/
 *     code.fd              optional: UEFI firmware to use instead of the one built into the app
 *     vars.fd              64 MB UEFI variable store (read/write, keeps Boot0001)
 *     windows11.img        raw disk image (or windows11.hvsparse, unpacked on first start)
 *     answers.iso          while installing from an ISO: the answer disc (install/AnswerDisc.kt)
 *   .../files/Share/       shared with Windows at http://10.0.2.4/
 */
class MachineStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("machine", Context.MODE_PRIVATE)

    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "Machines/Win11").apply { mkdirs() }
    val shareDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "Share").apply { mkdirs() }
    val shareSocket: File = File(context.cacheDir, "dav.sock")
    val logFile: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "winvm.log")
    val commandFile: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "commands.txt")

    val code = File(root, "code.fd")
    val vars = File(root, "vars.fd")
    val rawDisk = File(root, "windows11.img")
    val sparseDisk = File(root, "windows11.hvsparse")
    val installer = File(root, "installer.img")
    val answerDisc = File(root, "answers.iso")

    /** The app's own firmware (EDK2 ArmVirtQemu with the display-size patch; scripts/build_firmware.sh). */
    private val bundledFirmware = File(context.filesDir, "firmware/code.fd")
    private val hasBundledFirmware = try { context.assets.open(FIRMWARE_ASSET).close(); true } catch (_: java.io.IOException) { false }
    val hasCustomFirmware get() = code.isFile && code.length() > 0
    val hasFirmware get() = hasCustomFirmware || hasBundledFirmware
    val hasVars get() = vars.isFile && vars.length() > 0
    val hasDisk get() = rawDisk.isFile && rawDisk.length() > 0
    val needsUnpack get() = !hasDisk && sparseDisk.isFile
    val canBoot get() = hasFirmware && hasVars

    /** Physical RAM, in MB. */
    val deviceRamMb: Long = run {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.totalMem shr 20
    }
    val hostCores: Int = Runtime.getRuntime().availableProcessors()

    /** What the guest may have: leave Android (and the rest of the phone) a healthy share. */
    val maxRamMb: Long get() = ((deviceRamMb * 0.6).toLong() / 256 * 256).coerceAtLeast(1024)

    var cpus: Int
        get() = prefs.getInt("cpus", defaultCpus())
        set(v) = prefs.edit().putInt("cpus", v).apply()
    var ramMb: Long
        get() = prefs.getLong("ramMb", defaultRam()).coerceAtMost(maxRamMb)
        set(v) = prefs.edit().putLong("ramMb", v).apply()
    var network: Boolean
        get() = prefs.getBoolean("net", true)
        set(v) = prefs.edit().putBoolean("net", v).apply()
    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()
    var openLinksOnPhone: Boolean
        get() = prefs.getBoolean("openLinks", true)
        set(v) = prefs.edit().putBoolean("openLinks", v).apply()
    var shareFolder: Boolean
        get() = prefs.getBoolean("share", true)
        set(v) = prefs.edit().putBoolean("share", v).apply()
    var trackpadMode: Boolean
        get() = prefs.getBoolean("trackpad", true)
        set(v) = prefs.edit().putBoolean("trackpad", v).apply()
    var showStats: Boolean
        get() = prefs.getBoolean("stats", false)
        set(v) = prefs.edit().putBoolean("stats", v).apply()

    /** The Windows ISO being installed from (a document URI), until Windows reports it is installed. */
    var installIso: String?
        get() = prefs.getString("installIso", null)
        set(v) = prefs.edit().putString("installIso", v).apply()
    var installUser: String
        get() = prefs.getString("installUser", "User") ?: "User"
        set(v) = prefs.edit().putString("installUser", v).apply()
    val installing get() = installIso != null

    /** True when the disk has no partition table yet: a fresh disk, before Windows Setup ran. */
    fun diskIsBlank(): Boolean {
        if (!hasDisk) return true
        val head = ByteArray(1024)
        java.io.RandomAccessFile(rawDisk, "r").use { it.readFully(head) }
        val mbr = head[510] == 0x55.toByte() && head[511] == 0xAA.toByte()
        val gpt = String(head, 512, 8, Charsets.US_ASCII) == "EFI PART"
        return !mbr && !gpt
    }

    /** A new, empty (sparse) disk and variable store, for installing Windows onto. */
    fun createBlankDisk(bytes: Long) {
        rawDisk.delete()
        sparseDisk.delete()
        java.io.RandomAccessFile(rawDisk, "rw").use { it.setLength(bytes) }
        vars.delete()
        createBlankVars()
    }

    /** Free space where the disk lives. */
    val freeBytes: Long get() = root.usableSpace

    /** Galaxy S26 Ultra / Pixel 10 Pro class: 8 cores. Four vCPUs is the sweet spot WindowsiOS
        settled on (Windows idles at ~90% WFI); six where there are cores to spare. */
    private fun defaultCpus() = when {
        hostCores >= 8 -> 4
        hostCores >= 6 -> 4
        else -> (hostCores - 1).coerceAtLeast(1)
    }

    private fun defaultRam(): Long = when {
        deviceRamMb >= 15_000 -> 6144 // 16 GB phones
        deviceRamMb >= 11_000 -> 4608 // 12 GB phones: what WindowsiOS gives Windows
        deviceRamMb >= 7_500 -> 3072
        else -> 2048
    }.toLong().coerceAtMost(maxRamMb)

    /** A fresh 64 MB UEFI variable store. All zeros is not a valid firmware volume, so EDK2's
        flash driver formats it on first boot (verified with the ArmVirtQemu build). */
    fun createBlankVars() {
        java.io.RandomAccessFile(vars, "rw").use { it.setLength(64L shl 20) }
    }

    /** The firmware to boot: code.fd if one was copied in, else the app's own (unpacked once per app version). */
    fun firmware(): File {
        if (hasCustomFirmware) return code
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        val marker = File(bundledFirmware.parentFile, "version")
        if (!bundledFirmware.isFile || runCatching { marker.readText() }.getOrNull() != version) {
            bundledFirmware.parentFile!!.mkdirs()
            val tmp = File(bundledFirmware.parentFile, "code.fd.part")
            context.assets.open(FIRMWARE_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(bundledFirmware)) throw java.io.IOException("could not install the firmware")
            marker.writeText(version)
        }
        return bundledFirmware
    }

    /** Copy a document the user picked into the machine folder. */
    fun import(uri: Uri, dest: File, progress: (Long) -> Unit) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    progress(total)
                }
                out.fd.sync()
            }
        }
        if (!tmp.renameTo(dest)) throw java.io.IOException("could not move ${tmp.name} into place")
    }

    companion object {
        const val FIRMWARE_ASSET = "firmware/QEMU_EFI.fd"
    }
}
