package com.windowsandroid.install

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.windowsandroid.MachineStore
import java.io.FileInputStream

/**
 * Installing Windows from Microsoft's Windows 11 Arm64 ISO, on the phone, with no computer.
 *
 * The engine puts the ISO in a USB DVD drive and the app's answer disc in a second one, and
 * boots a blank disk; the firmware boots the DVD, Windows Setup finds the answer file and runs
 * unattended (two or three restarts), and the first sign-in tells the app it is done. The ISO is
 * read in place through the document provider's file descriptor; nothing is copied.
 */
object WindowsInstall {
    private const val TAG = "WindowsInstall"
    /** The disk Windows is installed onto. It is sparse: it takes only what Windows writes (~20 GB). */
    const val DISK_BYTES = 64L shl 30

    sealed class Checked {
        data class Ok(val info: InstallIso.Info, val size: Long) : Checked()
        data class Bad(val why: String) : Checked()
    }

    /** Open the ISO for the engine. It must be a real file the engine can read at any offset. */
    fun open(context: Context, uri: Uri): ParcelFileDescriptor {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.IOException("The ISO could not be opened.")
        val st = Os.fstat(pfd.fileDescriptor)
        if (!OsConstants.S_ISREG(st.st_mode)) {
            pfd.close()
            throw java.io.IOException("The ISO has to be on the phone itself: download it first, then choose it again.")
        }
        return pfd
    }

    /** What the ISO is: Windows 11 for Arm64, and in which language. */
    fun check(context: Context, uri: Uri): Checked = try {
        open(context, uri).use { pfd ->
            val size = Os.fstat(pfd.fileDescriptor).st_size
            val pvd = ByteArray(2048)
            FileInputStream(pfd.fileDescriptor).use { input ->
                input.channel.position(16 * 2048L)
                var n = 0
                while (n < pvd.size) {
                    val r = input.read(pvd, n, pvd.size - n)
                    if (r < 0) break
                    n += r
                }
            }
            val info = InstallIso.inspect(pvd)
            when {
                info == null -> Checked.Bad("This is not a disc image (ISO).")
                info.arch == "X64" || info.arch == "X86" ->
                    Checked.Bad("This is the ${if (info.arch == "X64") "x64" else "32-bit"} edition. WindowsAndroid needs Windows 11 for Arm64.")
                size < 3L shl 30 -> Checked.Bad("This ISO is too small to be Windows 11.")
                else -> Checked.Ok(info, size)
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "could not read $uri", t)
        Checked.Bad(t.message ?: "The ISO could not be read.")
    }

    /** Remember the ISO across restarts (Windows Setup restarts the machine; the app may be closed). */
    fun choose(context: Context, store: MachineStore, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (t: SecurityException) {
            Log.w(TAG, "no persistable permission for $uri", t)
        }
        store.installIso?.let { if (it != uri.toString()) release(context, Uri.parse(it)) }
        store.installIso = uri.toString()
    }

    /** Windows is installed (or the user gave up): forget the ISO and the answer disc. */
    fun finish(context: Context, store: MachineStore) {
        store.installIso?.let { release(context, Uri.parse(it)) }
        store.installIso = null
        store.answerDisc.delete()
    }

    private fun release(context: Context, uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
    }

    /** Write the answer disc for this phone: the ISO's language, the account name, the time zone. */
    fun writeAnswerDisc(context: Context, store: MachineStore, language: String) {
        val assets = context.assets
        fun text(path: String) = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        fun bytes(path: String) = assets.open(path).use { it.readBytes() }
        val netkvm = assets.list("install/netkvm").orEmpty().sorted().associateWith { bytes("install/netkvm/$it") }
        val a = AnswerDisc.Assets(
            answerTemplate = text("install/autounattend.xml"),
            setupScript = text("install/wasetup.cmd"),
            fitDisplay = bytes("fit-display.ps1"),
            netkvm = netkvm,
        )
        val o = AnswerDisc.Options(
            language = language,
            user = AnswerDisc.cleanUser(store.installUser),
            computer = "WINDOWS-ANDROID",
            timeZone = windowsTimeZone(),
        )
        val tmp = java.io.File(store.answerDisc.parentFile, store.answerDisc.name + ".part")
        tmp.outputStream().use { AnswerDisc.write(it, a, o) }
        if (!tmp.renameTo(store.answerDisc)) throw java.io.IOException("could not write the answer disc")
    }

    /** The phone's time zone, by its Windows name ("Pacific Standard Time"). */
    private fun windowsTimeZone(): String =
        android.icu.util.TimeZone.getWindowsID(java.util.TimeZone.getDefault().id) ?: "UTC"
}
