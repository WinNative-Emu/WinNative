package com.winlator.cmod.shared.io

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32

class ArchiveExtractorTest {
    private val entries =
        listOf(
            "Engine/Binaries/ThirdParty/Ordbook/Ordbook.dll" to "engine payload",
            "Engine/Extras/Redist/vcredist_x64.exe" to "redist payload",
            "MyGame/Binaries/Win64/MyGame.exe" to "game payload",
        )

    @Test
    fun extractsStoredEntriesWrittenWithDataDescriptors() {
        val root = Files.createTempDirectory("archive-extractor").toFile()
        val archive = File(root, "game.zip")
        archive.writeBytes(storedZipWithDataDescriptors(entries))

        val dest = File(root, "out")
        ArchiveExtractor.extract(archive, dest, {}, { true })

        entries.forEach { (name, body) ->
            assertEquals(body, File(dest, name).readText())
        }
        root.deleteRecursively()
    }

    private fun storedZipWithDataDescriptors(files: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        var offset = 0

        files.forEach { (name, body) ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val data = body.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(data) }.value

            val local = ByteArrayOutputStream()
            local.writeInt(0x04034b50)
            local.writeShort(20)
            local.writeShort(0x0008)
            local.writeShort(0)
            local.writeShort(0)
            local.writeShort(0x21)
            local.writeInt(0)
            local.writeInt(0)
            local.writeInt(0)
            local.writeShort(nameBytes.size)
            local.writeShort(0)
            local.write(nameBytes)
            local.write(data)
            local.writeInt(0x08074b50)
            local.writeInt(crc.toInt())
            local.writeInt(data.size)
            local.writeInt(data.size)

            central.writeInt(0x02014b50)
            central.writeShort(20)
            central.writeShort(20)
            central.writeShort(0x0008)
            central.writeShort(0)
            central.writeShort(0)
            central.writeShort(0x21)
            central.writeInt(crc.toInt())
            central.writeInt(data.size)
            central.writeInt(data.size)
            central.writeShort(nameBytes.size)
            central.writeShort(0)
            central.writeShort(0)
            central.writeShort(0)
            central.writeShort(0)
            central.writeInt(0)
            central.writeInt(offset)
            central.write(nameBytes)

            val localBytes = local.toByteArray()
            out.write(localBytes)
            offset += localBytes.size
        }

        val centralBytes = central.toByteArray()
        out.write(centralBytes)
        out.writeInt(0x06054b50)
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(files.size)
        out.writeShort(files.size)
        out.writeInt(centralBytes.size)
        out.writeInt(offset)
        out.writeShort(0)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }
}
