package com.winlator.cmod.runtime.audio.directaudio

import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectAudioAbiTest {

    private val assets = sequenceOf(
        File("src/main/assets/directaudio"),
        File("app/src/main/assets/directaudio"),
    ).first { it.isDirectory }

    private fun abiOf(archive: String): Int {
        ZipInputStream(File(assets, archive).inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "aarch64-unix/winedirectaudio.so") {
                    return DirectAudioDriver.unixCallEntries(zip.readBytes())
                }
                zip.closeEntry()
            }
        }
        throw AssertionError("no unixlib in $archive")
    }

    @Test
    fun everyBundledDriverAdvertisesAnAbi() {
        for (archive in assets.listFiles()!!.filter { it.name.endsWith(".zip") }) {
            assertTrue(
                "${archive.name} has no __wine_unix_call_funcs table",
                abiOf(archive.name) > 0,
            )
        }
    }

    @Test
    fun pageSizeVariantsShareOneAbi() {
        for (tag in listOf("wine10", "wine11")) {
            assertEquals(
                "$tag sdk28 and sdk35 must be the same driver built for two page sizes",
                abiOf("directaudio-$tag-arm64ec-sdk28.zip"),
                abiOf("directaudio-$tag-arm64ec-sdk35.zip"),
            )
        }
    }

    @Test
    fun theTwoDriverGenerationsAreDistinguishable() {
        val wine10 = abiOf("directaudio-wine10-arm64ec-sdk28.zip")
        val wine11 = abiOf("directaudio-wine11-arm64ec-sdk28.zip")
        assertEquals("Proton 9.0 and 10.0-4 both expose this table", 36, wine10)
        assertEquals("Proton 11.x exposes this table", 37, wine11)
        assertTrue("selection cannot work if both builds claim one ABI", wine10 != wine11)
    }

    @Test
    fun aTruncatedOrNonElfLibraryIsRejected() {
        assertEquals(0, DirectAudioDriver.unixCallEntries(ByteArray(0)))
        assertEquals(0, DirectAudioDriver.unixCallEntries("not an elf at all".toByteArray()))
        assertEquals(0, DirectAudioDriver.unixCallEntries(ByteArray(64) { 0x7F }))
    }
}
