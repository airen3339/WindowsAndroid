package com.windowsandroid.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class AnswerDiscTest {
    private fun assets(): AnswerDisc.Assets {
        val dir = File("src/main/assets/install")
        return AnswerDisc.Assets(
            answerTemplate = File(dir, "autounattend.xml").readText(),
            setupScript = File(dir, "wasetup.cmd").readText(),
            fitDisplay = File("../guest/fit-display.ps1").readBytes(),
            netkvm = File(dir, "netkvm").listFiles()!!.sortedBy { it.name }.associate { it.name to it.readBytes() },
        )
    }

    @Test
    fun labels() {
        assertEquals("en-US", InstallIso.languageOf("CCCOMA_A64FRE_EN-US_DV9"))
        assertEquals("en-US", InstallIso.languageOf("CCCOMA_A64FRE_EN_US_DV9"))
        assertEquals("de-DE", InstallIso.languageOf("CCCOMA_A64FRE_DE-DE_DV9"))
        assertEquals("A64", InstallIso.archOf("CCCOMA_A64FRE_EN-US_DV9"))
        assertEquals("X64", InstallIso.archOf("CCCOMA_X64FRE_EN-GB_DV9"))
        assertNull(InstallIso.languageOf("MY_DISC"))
        assertEquals("Ana", AnswerDisc.cleanUser(" Ana<> "))
        assertEquals("MY-PHONE", AnswerDisc.cleanComputer("my phone"))
    }

    /** Writes build/answer-test.iso, which scripts boot with the macOS runner to test an installation. */
    @Test
    fun answerDisc() {
        val o = AnswerDisc.Options("en-US", "User", "WINDOWS-ANDROID", "UTC")
        val out = ByteArrayOutputStream()
        val size = AnswerDisc.write(out, assets(), o)
        val image = out.toByteArray()
        assertEquals(size, image.size.toLong())
        assertEquals("CD001", String(image, 16 * 2048 + 1, 5))
        val info = InstallIso.inspect(image.copyOfRange(16 * 2048, 17 * 2048))!!
        assertEquals("WA_ANSWERS", info.label)
        val text = String(image, Charsets.ISO_8859_1)
        assertTrue(text.contains("<UILanguage>en-US</UILanguage>"))
        assertTrue(!text.contains("@USER@"))
        File("build").mkdirs()
        File("build/answer-test.iso").writeBytes(image)
    }
}
