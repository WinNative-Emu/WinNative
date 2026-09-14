package com.winlator.cmod.runtime.audio.directaudio

import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectAudioDriverExportsTest {

    private val assets = sequenceOf(
        File("src/main/assets/directaudio"),
        File("app/src/main/assets/directaudio"),
    ).first { it.isDirectory }

    private fun drvExports(archive: String): Map<String, List<String>> {
        val found = LinkedHashMap<String, List<String>>()
        ZipInputStream(File(assets, archive).inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.endsWith("winedirectaudio.drv")) {
                    found[entry.name] = DirectAudioDriver.peExportNames(zip.readBytes())
                }
                zip.closeEntry()
            }
        }
        assertEquals("both PE halves present in $archive", 2, found.size)
        return found
    }

    @Test
    fun wine10BuildsExposeTheMmdevapiEntryPoints() {
        for (archive in listOf(
            "directaudio-wine10-arm64ec-sdk28.zip",
            "directaudio-wine10-arm64ec-sdk35.zip",
        )) {
            for ((name, exports) in drvExports(archive)) {
                assertTrue(
                    "$archive/$name exports $exports, missing ${DirectAudioDriver.MMDEVAPI_ENTRY_POINTS}",
                    exports.containsAll(DirectAudioDriver.MMDEVAPI_ENTRY_POINTS),
                )
            }
        }
    }

    @Test
    fun wine11BuildsAreStubsAndMustBeRejected() {
        for (archive in listOf(
            "directaudio-wine11-arm64ec-sdk28.zip",
            "directaudio-wine11-arm64ec-sdk35.zip",
        )) {
            for ((name, exports) in drvExports(archive)) {
                assertTrue(
                    "$archive/$name now exports $exports. If upstream has fixed the Wine 11 " +
                        "artifacts, refresh the bundled zips and delete this expectation so " +
                        "DirectAudio is offered on Proton 11 again.",
                    exports.isEmpty(),
                )
            }
        }
    }
}
