package com.winlator.cmod.runtime.linux

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.util.zip.GZIPOutputStream

class LinuxProtonsTest {
    @Test
    fun rejectsTraversalAndX86BuildsWithoutLeavingPartialInstallation() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.cacheDir, "invalid-proton.tar.gz")
        val build = LinuxProtons.Build("invalid-proton", "Invalid", "https://example.invalid/proton.tar.gz", "", 4096)
        val x86 = ByteArray(20).apply {
            this[0] = 127; this[1] = 69; this[2] = 76; this[3] = 70; this[4] = 2; this[18] = 62
        }
        val cases = listOf(mapOf("../escaped-proton" to byteArrayOf(1)), mapOf("tree/proton" to byteArrayOf(1), "tree/files/bin-arm64/wine" to x86))
        for (entries in cases) {
            TarArchiveOutputStream(GZIPOutputStream(archive.outputStream())).use { tar ->
                entries.forEach { (name, bytes) ->
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
            assertTrue(runCatching { LinuxProtons.installArchive(context, build, archive) }.isFailure)
            assertFalse(File(LinuxProtons.directory(context), "escaped-proton").exists())
            assertFalse(File(LinuxProtons.directory(context), build.id).exists())
            assertFalse(File(LinuxProtons.directory(context), ".${build.id}.staging").exists())
        }
        archive.delete()
    }

    @Test
    fun installsRealArmProtonArchiveAndRemovesOnlyThatBuild() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.filesDir, "proton-test")
        val json = JSONObject(File(fixture, "build.json").readText())
        val build = LinuxProtons.Build(json.getString("id"), json.getString("name"), json.getString("url"), json.getString("sha256"), json.getLong("size"))
        val target = File(LinuxProtons.directory(context), build.id)
        assertFalse(target.exists())
        try {
            LinuxProtons.installArchive(context, build, File(fixture, "archive"))
            assertTrue(File(target, "proton").canExecute())
            assertTrue(File(target, "files/bin-arm64/wine").canExecute())
            assertEquals(build.id, JSONObject(File(target, "winnative-proton.json").readText()).getString("id"))
            assertTrue(File(target, "toolmanifest.vdf").readText().contains("/winnative-proton-wrap %verb%"))
            assertTrue(File(target, "winnative-proton-wrap").readText().contains("winnative-proton-launch"))
            assertFalse(LinuxProtons.directory(context).listFiles().orEmpty().any { it.name.endsWith(".staging") })
            LinuxProtons.remove(context, build)?.join()
            assertFalse(target.exists())
        } finally {
            target.deleteRecursively()
        }
    }
}
