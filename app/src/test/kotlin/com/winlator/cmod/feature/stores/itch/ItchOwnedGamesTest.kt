package com.winlator.cmod.feature.stores.itch

import com.winlator.cmod.feature.stores.itch.service.ItchOwnedGames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItchOwnedGamesTest {
    private val purchaseRows =
        """
        <div class="main"><div class="purchase_list_widget">
        <div class="game_row" data-game_id="4892272">
        <a class="thumb_link" href="https://nincy12.itch.io/feed-the-fish">
        <img src="https://img.itch.zone/cover-one.png"/></a>
        <div class="game_title"><a href="https://nincy12.itch.io/feed-the-fish">FEED THE FISH</a></div>
        <div class="game_author"><a href="https://nincy12.itch.io">Nincy</a></div>
        </div>
        <div class="game_row" data-game_id="3609548">
        <a class="thumb_link" href="https://abho.itch.io/cobb-can-move">
        <img data-lazy_src="https://img.itch.zone/cover-two.png"/></a>
        <div class="game_title"><a href="https://abho.itch.io/cobb-can-move">COBB CAN MOVE</a></div>
        </div>
        </div></div>
        """.trimIndent()

    private val emptyLibrary =
        """
        <div class="header_widget"><a class="browse_btn" href="https://itch.io/games">Browse Games</a>
        <a href="https://itch.io/my-purchases">My purchases</a>
        <a href="https://itch.io/my-collections">My collections</a></div>
        <div class="main"><p>You haven't bought anything yet.</p></div>
        """.trimIndent()

    @Test
    fun parsesPurchaseRowsThatAreNotBrowseCells() {
        val games = ItchOwnedGames.parseRows(purchaseRows)
        assertEquals(2, games.size)
        assertEquals("FEED THE FISH", games[0].title)
        assertEquals("https://nincy12.itch.io/feed-the-fish", games[0].url)
        assertEquals(4892272, games[0].id)
        assertEquals("https://img.itch.zone/cover-one.png", games[0].coverUrl)
        assertEquals("nincy12", games[0].author)
        assertEquals("COBB CAN MOVE", games[1].title)
        assertEquals(3609548, games[1].id)
    }

    @Test
    fun deduplicatesRepeatedLinksToTheSameGame() {
        val games = ItchOwnedGames.parseRows(purchaseRows + purchaseRows)
        assertEquals(2, games.size)
    }

    @Test
    fun ignoresSiteNavigationOnAnEmptyLibrary() {
        assertTrue(ItchOwnedGames.parseRows(emptyLibrary).isEmpty())
    }

    @Test
    fun ignoresDownloadAndPurchaseSubpages() {
        val html =
            """
            <div class="main">
            <a href="https://abho.itch.io/cobb-can-move/download">Download</a>
            <a href="https://abho.itch.io/cobb-can-move/purchase">Buy</a>
            </div>
            """.trimIndent()
        assertTrue(ItchOwnedGames.parseRows(html).isEmpty())
    }
}
