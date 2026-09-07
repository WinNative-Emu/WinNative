package com.winlator.cmod.feature.stores.itch

import com.winlator.cmod.feature.stores.itch.service.ItchCatalog
import com.winlator.cmod.shared.io.ArchiveExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

class ItchUploadUpdateTest {
    private val downloadPage =
        """
        <div class="uploads"><div id="upload_list_8277761" class="upload_list_widget base_widget"><div class="upload">
        <a href="javascript:void(0);" class="button download_btn" data-upload_id="17598815">Download</a>
        <div class="info_column"><div class="upload_name">
        <strong title="COBB CAN MOVE v1.7 webview2.zip" class="name">COBB CAN MOVE v1.7 webview2.zip</strong>
        <span class="file_size"><span>15 MB</span></span>
        <span class="download_platforms"><span title="Download for Windows" aria-hidden="true" class="icon icon-windows8"></span></span>
        </div>
        <div class="upload_date"><abbr title="19 May 2026 @ 13:12 UTC"><span aria-hidden="true" class="icon icon-stopwatch"></span> May 19, 2026</abbr></div>
        </div></div></div></div>
        """.trimIndent()

    @Test
    fun readsTheUploadTimestampThatIdentifiesABuild() {
        val upload = ItchCatalog.parseUploads(downloadPage).single()
        assertEquals(17598815L, upload.id)
        assertEquals("19 May 2026 @ 13:12 UTC", upload.uploadedAt)
        assertEquals("19 May 2026 @ 13:12 UTC", upload.buildLabel)
        assertEquals(15L * 1024 * 1024, upload.sizeBytes)
    }

    @Test
    fun fallsBackToTheFileNameWhenNoBuildMarkerIsPublished() {
        val bare = downloadPage.replace(Regex("<div class=\"upload_date\">.*?</div>", RegexOption.DOT_MATCHES_ALL), "")
        val upload = ItchCatalog.parseUploads(bare).single()
        assertEquals("", upload.uploadedAt)
        assertEquals("COBB CAN MOVE v1.7 webview2.zip", upload.buildLabel)
    }

    @Test
    fun extractingAnUpdateOverAnInstallKeepsSaveDataAndReplacesGameFiles() {
        val root = Files.createTempDirectory("itch-update").toFile()
        val installDir = File(root, "game").apply { mkdirs() }

        File(installDir, "Game.exe").writeText("v1")
        File(installDir, "data").mkdirs()
        File(installDir, "data/assets.pak").writeText("old assets")
        File(installDir, "saves").mkdirs()
        File(installDir, "saves/slot1.sav").writeText("player progress")
        File(installDir, "settings.ini").writeText("volume=7")

        val update = File(root, "update.zip")
        ZipArchiveOutputStream(BufferedOutputStream(FileOutputStream(update))).use { zip ->
            listOf("Game.exe" to "v2", "data/assets.pak" to "new assets", "data/extra.pak" to "added").forEach { (name, body) ->
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(body.toByteArray())
                zip.closeArchiveEntry()
            }
        }

        ArchiveExtractor.extract(update, installDir, {}, { true })

        assertEquals("v2", File(installDir, "Game.exe").readText())
        assertEquals("new assets", File(installDir, "data/assets.pak").readText())
        assertEquals("added", File(installDir, "data/extra.pak").readText())
        assertTrue("save file was destroyed by the update", File(installDir, "saves/slot1.sav").isFile)
        assertEquals("player progress", File(installDir, "saves/slot1.sav").readText())
        assertEquals("volume=7", File(installDir, "settings.ini").readText())
        root.deleteRecursively()
    }
}
