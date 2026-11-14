package com.windowsandroid.install

import java.io.OutputStream

/**
 * The answer disc: what turns Microsoft's Windows 11 Arm64 ISO into a hands-off installation.
 * Windows Setup looks for Autounattend.xml on removable media by itself; the answer file skips
 * every question, gets past Windows 11's TPM / Secure Boot checks, and runs WASETUP.CMD from
 * this disc on the first boot to install the NetKVM network driver and the display helper.
 */
object AnswerDisc {
    data class Options(
        /** Windows language tag of the ISO, e.g. "en-US". */
        val language: String,
        val user: String,
        val computer: String,
        /** Windows time zone name, e.g. "Pacific Standard Time". */
        val timeZone: String,
    )

    /** The parts that come from the app's assets. */
    class Assets(
        val answerTemplate: String,
        val setupScript: String,
        val fitDisplay: ByteArray,
        /** netkvm.inf, netkvm.sys, netkvm.cat, netkvmp.exe and the license, by file name. */
        val netkvm: Map<String, ByteArray>,
    )

    const val VOLUME = "WA_ANSWERS"

    fun files(assets: Assets, o: Options): Map<String, ByteArray> {
        val answer = assets.answerTemplate
            .replace("@LANG@", xml(o.language))
            .replace("@USER@", xml(o.user))
            .replace("@COMPUTER@", xml(o.computer))
            .replace("@TIMEZONE@", xml(o.timeZone))
        val files = linkedMapOf(
            "Autounattend.xml" to answer.toByteArray(Charsets.UTF_8),
            "WASETUP.CMD" to crlf(assets.setupScript).toByteArray(Charsets.US_ASCII),
            "FITDISP.PS1" to assets.fitDisplay,
        )
        for ((name, bytes) in assets.netkvm) {
            val disc = if (name.equals("LICENSE.txt", ignoreCase = true)) "NETKVM_LICENSE.TXT" else name.uppercase()
            files[disc] = bytes
        }
        return files
    }

    fun write(out: OutputStream, assets: Assets, o: Options): Long = IsoImage.write(out, VOLUME, files(assets, o))

    /** A Windows account name: up to 20 characters, none of " / \ [ ] : ; | = , + * ? < > @. */
    fun cleanUser(name: String): String =
        name.trim().filter { it >= ' ' && it !in "\"/\\[]:;|=,+*?<>@%" }.take(20).trimEnd('.', ' ').ifEmpty { "User" }

    /** A NetBIOS computer name: up to 15 letters, digits and hyphens, not all digits. */
    fun cleanComputer(name: String): String {
        val c = name.uppercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }.joinToString("")
            .trim('-').take(15).trimEnd('-')
        return if (c.isEmpty() || c.all { it.isDigit() }) "WINDOWS-ANDROID" else c
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun crlf(s: String) = s.replace("\r\n", "\n").replace("\n", "\r\n")
}

/** What the app can tell about an ISO from its volume label (sector 16, the primary volume descriptor). */
object InstallIso {
    data class Info(val label: String, val language: String, val arch: String?)

    /** [pvd] is the 2048 bytes at offset 32768. Null if this is not an ISO 9660 / UDF disc image. */
    fun inspect(pvd: ByteArray): Info? {
        if (pvd.size < 72 || pvd[0].toInt() != 1 || String(pvd, 1, 5, Charsets.US_ASCII) != "CD001") return null
        val label = String(pvd, 40, 32, Charsets.US_ASCII).trim { it <= ' ' }
        return Info(label, languageOf(label) ?: "en-US", archOf(label))
    }

    /** "CCCOMA_A64FRE_EN-US_DV9" -> "A64"; Microsoft's labels name the architecture. */
    fun archOf(label: String): String? = Regex("_(A64|X64|X86)FRE[EV]?_", RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.uppercase()

    /** "CCCOMA_A64FRE_EN-US_DV9" -> "en-US" (also "EN_US", which some tools write instead). */
    fun languageOf(label: String): String? {
        val m = Regex("FRE[EV]?_([A-Za-z]{2,3})[-_]([A-Za-z]{2,4})(?=_|$)").find(label) ?: return null
        val (lang, region) = m.destructured
        val r = if (region.length == 4) region.lowercase().replaceFirstChar { it.uppercase() } else region.uppercase()
        return "${lang.lowercase()}-$r"
    }
}
