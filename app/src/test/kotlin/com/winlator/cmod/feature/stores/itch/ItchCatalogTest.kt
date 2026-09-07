package com.winlator.cmod.feature.stores.itch

import com.winlator.cmod.feature.stores.itch.data.ItchGame
import com.winlator.cmod.feature.stores.itch.data.ItchGameDetails
import com.winlator.cmod.feature.stores.itch.data.ItchPlatform
import com.winlator.cmod.feature.stores.itch.service.ItchCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItchCatalogTest {
    private val uploadClassFirst =
        """
        <div class="uploads"><div class="upload_list_widget base_widget" id="upload_list_3933492"><div class="upload">
        <a class="button download_btn" href="javascript:void(0);" data-upload_id="18813527">Download</a>
        <div class="info_column"><div class="upload_name">
        <strong class="name" title="feed-the-fish-windows.zip">feed-the-fish-windows.zip</strong>
        <span class="file_size"><span>696 MB</span></span>
        <span class="download_platforms"><span class="icon icon-windows8" aria-hidden="true" title="Download for Windows"></span> </span>
        </div><div class="build_row"><span class="version_name">Version 1.3</span></div></div></div></div></div>
        """.trimIndent()

    private val uploadTitleFirst =
        """
        <div class="uploads"><div class="upload_list_widget base_widget" id="upload_list_1"><div class="upload">
        <a data-upload_id="17598815" class="button download_btn" href="javascript:void(0);">Download</a>
        <div class="info_column"><div class="upload_name">
        <strong title="COBB CAN MOVE v1.7 webview2.zip" class="name">COBB CAN MOVE v1.7 webview2.zip</strong>
        <span class="file_size"><span>15 MB</span></span>
        <span class="download_platforms"><span aria-hidden="true" title="Download for Windows" class="icon icon-windows8"></span> </span>
        </div></div></div></div></div>
        """.trimIndent()

    private val cellClassFirst =
        """
        <div class="game_cell has_cover lazy_images" data-game_id="4958779" dir="auto"><div class="game_thumb">
        <a class="thumb_link game_link" href="https://exzimio.itch.io/orbita"><img height="250"
        data-lazy_src="https://img.itch.zone/cover.png" class="lazy_loaded" width="315"/></a></div>
        <div class="game_cell_data"><div class="game_title">
        <a class="title game_link" data-label="game:4958779:title" href="https://exzimio.itch.io/orbita">Orbita</a></div>
        <div class="game_text" title="desc">A tap pushes you away from the center</div>
        <div class="game_author"><a href="https://exzimio.itch.io">exzimio</a></div>
        <div class="game_genre">Action</div>
        <div class="game_platform"><span class="icon icon-windows8" aria-hidden="true" title="Download for Windows"></span></div>
        </div></div>
        """.trimIndent()

    private val cellIdFirst =
        """
        <div data-game_id="4892272" dir="auto" class="game_cell has_cover lazy_images"><div class="game_thumb">
        <a data-label="game:4892272:thumb" class="thumb_link game_link" href="https://nincy12.itch.io/feed-the-fish">
        <img data-lazy_src="https://img.itch.zone/cover2.png" width="315" height="250"/></a></div>
        <div class="game_cell_data"><div class="game_title">
        <a data-label="game:4892272:title" href="https://nincy12.itch.io/feed-the-fish" class="title game_link">FEED THE FISH</a></div>
        <div title="desc" class="game_text">Feed the fish. Do not look out the window.</div>
        <div class="game_author"><a href="https://nincy12.itch.io">Nincy</a></div>
        <div class="game_genre">Adventure</div>
        <div class="game_platform"><span title="Download for Windows" aria-hidden="true" class="icon icon-windows8"></span></div>
        <div class="price_tag meta_tag"><div class="price_value">${'$'}3.99</div></div>
        </div></div>
        """.trimIndent()

    @Test
    fun parsesUploadsWhenClassAttributeComesFirst() {
        val uploads = ItchCatalog.parseUploads(uploadClassFirst)
        assertEquals(1, uploads.size)
        assertEquals(18813527L, uploads[0].id)
        assertEquals("feed-the-fish-windows.zip", uploads[0].fileName)
        assertEquals("696 MB", uploads[0].sizeLabel)
        assertEquals("Version 1.3", uploads[0].version)
        assertTrue(ItchPlatform.WINDOWS in uploads[0].platforms)
    }

    @Test
    fun parsesUploadsWhenTitleAttributeComesFirst() {
        val uploads = ItchCatalog.parseUploads(uploadTitleFirst)
        assertEquals(1, uploads.size)
        assertEquals(17598815L, uploads[0].id)
        assertEquals("COBB CAN MOVE v1.7 webview2.zip", uploads[0].fileName)
        assertEquals("15 MB", uploads[0].sizeLabel)
        assertTrue(ItchPlatform.WINDOWS in uploads[0].platforms)
    }

    @Test
    fun parsesGameCellsRegardlessOfAttributeOrder() {
        val first = ItchCatalog.parseGameCells(cellClassFirst)
        assertEquals(1, first.size)
        assertEquals(4958779, first[0].id)
        assertEquals("Orbita", first[0].title)
        assertEquals("https://exzimio.itch.io/orbita", first[0].url)
        assertEquals("exzimio", first[0].author)
        assertEquals("Action", first[0].genre)
        assertEquals("https://img.itch.zone/cover.png", first[0].coverUrl)
        assertTrue(first[0].hasWindowsBuild)
        assertTrue(first[0].isFree)

        val second = ItchCatalog.parseGameCells(cellIdFirst)
        assertEquals(1, second.size)
        assertEquals(4892272, second[0].id)
        assertEquals("FEED THE FISH", second[0].title)
        assertEquals("https://nincy12.itch.io/feed-the-fish", second[0].url)
        assertEquals("Nincy", second[0].author)
        assertEquals("Adventure", second[0].genre)
        assertEquals("https://img.itch.zone/cover2.png", second[0].coverUrl)
        assertTrue(second[0].hasWindowsBuild)
        assertEquals("${'$'}3.99", second[0].priceLabel)
    }

    @Test
    fun parsesBothCellsFromOneMixedPage() {
        val games = ItchCatalog.parseGameCells(cellClassFirst + cellIdFirst)
        assertEquals(2, games.size)
        assertEquals(listOf(4958779, 4892272), games.map { it.id })
    }

    @Test
    fun ranksWindowsArchiveAboveOtherUploads() {
        val uploads = ItchCatalog.parseUploads(uploadClassFirst + uploadTitleFirst)
        assertEquals(2, uploads.size)
        assertEquals(18813527L, ItchCatalog.pickWindowsUpload(uploads)?.id)
    }

    @Test
    fun parsesBinarySizeLabels() {
        assertEquals(696L * 1024 * 1024, ItchCatalog.parseSize("696 MB"))
        assertEquals((1.1 * 1024 * 1024 * 1024).toLong(), ItchCatalog.parseSize("1.1 GB"))
        assertEquals(0L, ItchCatalog.parseSize(""))
    }

    private val infoPanel =
        """
        <div class="game_info_panel_widget base_widget"><table>
        <tr><td>Status</td><td>Released</td></tr>
        <tr><td>Platforms</td><td><a href="https://itch.io/games/platform-windows">Windows</a></td></tr>
        <tr><td>Genre</td><td><a href="https://itch.io/games/genre-survival">Survival</a></td></tr>
        <tr><td>Tags</td><td><a href="https://itch.io/games/tag-2d">2D</a>, <a href="https://itch.io/games/tag-horror">Horror</a></td></tr>
        <tr><td>Inputs</td><td><a href="https://itch.io/games/input-keyboard">Keyboard</a>, <a href="https://itch.io/games/input-gamepad">Gamepad (any)</a></td></tr>
        </table></div>
        """.trimIndent()

    private val keyboardOnlyPanel =
        """
        <div class="game_info_panel_widget base_widget"><table>
        <tr><td>Inputs</td><td><a href="https://itch.io/games/input-keyboard">Keyboard</a>, <a href="https://itch.io/games/input-mouse">Mouse</a></td></tr>
        </table></div>
        """.trimIndent()

    @Test
    fun readsInputsFromTheInfoPanel() {
        val inputs = ItchCatalog.infoLinks(infoPanel, "Inputs")
        assertEquals(listOf("input-keyboard", "input-gamepad"), inputs.map { it.slug })
        assertEquals(listOf("Keyboard", "Gamepad (any)"), inputs.map { it.label })
    }

    @Test
    fun flagsControllerSupportFromInputSlugs() {
        val details = ItchGameDetails(game = ItchGame(1, "t", "https://a.itch.io/b"), inputs = ItchCatalog.infoLinks(infoPanel, "Inputs"))
        assertTrue(details.inputsKnown)
        assertTrue(details.hasControllerSupport)
        assertEquals(listOf("Gamepad (any)"), details.controllerInputs.map { it.label })

        val keyboardOnly =
            ItchGameDetails(game = ItchGame(1, "t", "https://a.itch.io/b"), inputs = ItchCatalog.infoLinks(keyboardOnlyPanel, "Inputs"))
        assertTrue(keyboardOnly.inputsKnown)
        assertFalse(keyboardOnly.hasControllerSupport)
    }

    @Test
    fun readsTagsWithoutBleedingIntoNeighbouringRows() {
        assertEquals(listOf("2D", "Horror"), ItchCatalog.infoLinks(infoPanel, "Tags").map { it.label })
        assertEquals(listOf("Survival"), ItchCatalog.infoLinks(infoPanel, "Genre").map { it.label })
        assertTrue(ItchCatalog.infoLinks(infoPanel, "Languages").isEmpty())
    }
}
