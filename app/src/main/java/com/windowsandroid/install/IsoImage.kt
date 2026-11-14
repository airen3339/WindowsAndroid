package com.windowsandroid.install

import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * A small ISO 9660 disc image with Joliet names: one root directory of files. The app writes
 * its answer disc with it (Windows Setup's answer file, the network driver and a setup script),
 * which the engine puts in the second USB DVD drive.
 */
object IsoImage {
    private const val SECTOR = 2048

    /** Write [files] (name to content) as a disc labelled [volumeId] to [out]. Returns its size. */
    fun write(out: OutputStream, volumeId: String, files: Map<String, ByteArray>): Long {
        val names = files.keys.map { it.uppercase() }
        require(names.toSet().size == names.size) { "duplicate file names" }
        val entries = files.entries.sortedBy { it.key.uppercase() }

        // Sectors: 16 PVD, 17 Joliet SVD, 18 terminator, 19-22 path tables, 23 ISO root, 24 Joliet root, then files.
        val isoRoot = 23
        val jolietRoot = 24
        var next = 25
        val extents = entries.map { e ->
            val at = next
            next += ((e.value.size + SECTOR - 1) / SECTOR).coerceAtLeast(1)
            at
        }
        val total = next

        val isoDir = directory(isoRoot, isoRoot, entries.mapIndexed { i, e -> Triple(isoName(e.key), extents[i], e.value.size) }, joliet = false)
        val jolietDir = directory(jolietRoot, jolietRoot, entries.mapIndexed { i, e -> Triple(jolietName(e.key), extents[i], e.value.size) }, joliet = true)

        val image = ByteArray(25 * SECTOR)
        descriptor(image, 16 * SECTOR, 1, volumeId, total, isoRoot, 19, 20, joliet = false)
        descriptor(image, 17 * SECTOR, 2, volumeId, total, jolietRoot, 21, 22, joliet = true)
        image[18 * SECTOR] = 255.toByte()
        "CD001".toByteArray(StandardCharsets.US_ASCII).copyInto(image, 18 * SECTOR + 1)
        image[18 * SECTOR + 6] = 1
        pathTable(image, 19 * SECTOR, isoRoot, littleEndian = true)
        pathTable(image, 20 * SECTOR, isoRoot, littleEndian = false)
        pathTable(image, 21 * SECTOR, jolietRoot, littleEndian = true)
        pathTable(image, 22 * SECTOR, jolietRoot, littleEndian = false)
        isoDir.copyInto(image, isoRoot * SECTOR)
        jolietDir.copyInto(image, jolietRoot * SECTOR)
        out.write(image)
        for ((i, e) in entries.withIndex()) {
            out.write(e.value)
            val sectors = (extents.getOrElse(i + 1) { total }) - extents[i]
            val pad = sectors * SECTOR - e.value.size
            if (pad > 0) out.write(ByteArray(pad))
        }
        return total.toLong() * SECTOR
    }

    /** ISO 9660 level 2 name: upper case, the version suffix ";1". */
    private fun isoName(name: String): ByteArray =
        (name.uppercase().map { if (it.isLetterOrDigit() || it == '.' || it == '_') it else '_' }
            .joinToString("") + ";1").toByteArray(StandardCharsets.US_ASCII)

    private fun jolietName(name: String): ByteArray = "$name;1".toByteArray(StandardCharsets.UTF_16BE)

    private fun directory(self: Int, parent: Int, files: List<Triple<ByteArray, Int, Int>>, joliet: Boolean): ByteArray {
        val d = ByteArray(SECTOR)
        var at = 0
        at += record(d, at, byteArrayOf(0), self, SECTOR, dir = true)
        at += record(d, at, byteArrayOf(1), parent, SECTOR, dir = true)
        for ((name, extent, size) in files) {
            val len = 33 + name.size + (if (name.size % 2 == 0) 1 else 0)
            check(at + len <= SECTOR) { "too many files for one directory sector" }
            at += record(d, at, name, extent, size, dir = false)
        }
        return d
    }

    private fun record(d: ByteArray, at: Int, name: ByteArray, extent: Int, size: Int, dir: Boolean): Int {
        val len = 33 + name.size + (if (name.size % 2 == 0) 1 else 0)
        d[at] = len.toByte()
        both32(d, at + 2, extent)
        both32(d, at + 10, size)
        recordingDate(d, at + 18)
        d[at + 25] = if (dir) 2 else 0
        both16(d, at + 28, 1)
        d[at + 32] = name.size.toByte()
        name.copyInto(d, at + 33)
        return len
    }

    private fun descriptor(d: ByteArray, at: Int, type: Int, volumeId: String, sectors: Int, root: Int, lTable: Int, mTable: Int, joliet: Boolean) {
        d[at] = type.toByte()
        "CD001".toByteArray(StandardCharsets.US_ASCII).copyInto(d, at + 1)
        d[at + 6] = 1
        text(d, at + 8, 32, "", joliet)
        text(d, at + 40, 32, volumeId, joliet)
        both32(d, at + 80, sectors)
        if (joliet) byteArrayOf(0x25, 0x2f, 0x45).copyInto(d, at + 88) // "%/E": UCS-2 level 3
        both16(d, at + 120, 1)
        both16(d, at + 124, 1)
        both16(d, at + 128, SECTOR)
        both32(d, at + 132, 10)
        le32(d, at + 140, lTable)
        be32(d, at + 148, mTable)
        record(d, at + 156, byteArrayOf(0), root, SECTOR, dir = true)
        for ((off, len) in listOf(190 to 128, 318 to 128, 446 to 128, 574 to 128, 702 to 37, 739 to 37, 776 to 37)) text(d, at + off, len, "", joliet)
        text(d, at + 574, 128, "WINDOWSANDROID", joliet)
        for (off in listOf(813, 830)) "2026010100000000".toByteArray(StandardCharsets.US_ASCII).copyInto(d, at + off)
        for (off in listOf(847, 864)) "0000000000000000".toByteArray(StandardCharsets.US_ASCII).copyInto(d, at + off)
        d[at + 881] = 1
    }

    private fun pathTable(d: ByteArray, at: Int, root: Int, littleEndian: Boolean) {
        d[at] = 1
        if (littleEndian) le32(d, at + 2, root) else be32(d, at + 2, root)
        d[at + (if (littleEndian) 6 else 7)] = 1 // parent directory number 1 (itself)
    }

    private fun text(d: ByteArray, at: Int, len: Int, s: String, joliet: Boolean) {
        if (joliet) {
            for (i in 0 until len / 2) {
                val c = if (i < s.length) s[i] else ' '
                d[at + 2 * i] = (c.code shr 8).toByte()
                d[at + 2 * i + 1] = c.code.toByte()
            }
        } else {
            for (i in 0 until len) d[at + i] = (if (i < s.length) s[i] else ' ').code.toByte()
        }
    }

    private fun recordingDate(d: ByteArray, at: Int) {
        d[at] = 126; d[at + 1] = 1; d[at + 2] = 1 // 2026-01-01 00:00:00 UTC
    }

    private fun le32(d: ByteArray, at: Int, v: Int) { for (i in 0..3) d[at + i] = (v ushr (8 * i)).toByte() }
    private fun be32(d: ByteArray, at: Int, v: Int) { for (i in 0..3) d[at + i] = (v ushr (24 - 8 * i)).toByte() }
    private fun both32(d: ByteArray, at: Int, v: Int) { le32(d, at, v); be32(d, at + 4, v) }
    private fun both16(d: ByteArray, at: Int, v: Int) {
        d[at] = v.toByte(); d[at + 1] = (v ushr 8).toByte(); d[at + 2] = (v ushr 8).toByte(); d[at + 3] = v.toByte()
    }
}
