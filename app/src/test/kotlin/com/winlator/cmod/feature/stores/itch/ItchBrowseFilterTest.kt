package com.winlator.cmod.feature.stores.itch

import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.itch.data.ItchBrowseFilter
import com.winlator.cmod.feature.stores.itch.data.ItchFacet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItchBrowseFilterTest {
    private fun facet(segment: String) = ItchFacet.visible(true).first { it.segment == segment }

    @Test
    fun defaultBrowseIsFreeWindowsOnly() {
        assertEquals("games/free/platform-windows", ItchBrowseFilter().toPath())
        assertTrue(ItchBrowseFilter().filtersWindowsServerSide)
        assertEquals("games/free", ItchBrowseFilter(windowsOnly = false).toPath())
    }

    @Test
    fun sortedAndTaggedBrowsesDropTheWindowsSegmentToStayUnderTheLimit() {
        val newest = ItchBrowseFilter(facet("newest"))
        assertEquals("games/newest/free", newest.toPath())
        assertFalse(newest.filtersWindowsServerSide)
        val rpg = ItchBrowseFilter(facet("genre-rpg"))
        assertEquals("games/free/genre-rpg", rpg.toPath())
        assertFalse(rpg.filtersWindowsServerSide)
    }

    @Test
    fun sortFacetsPrecedeTheFreeSegment() {
        assertEquals("games/newest/free", ItchBrowseFilter(facet("newest")).toPath())
        assertEquals("games/top-rated/free", ItchBrowseFilter(facet("top-rated")).toPath())
        assertEquals("games/new-and-popular/free", ItchBrowseFilter(facet("new-and-popular")).toPath())
    }

    @Test
    fun genreAndTagFacetsFollowTheFreeSegment() {
        assertEquals("games/free/genre-rpg", ItchBrowseFilter(facet("genre-rpg")).toPath())
        assertEquals("games/free/tag-horror", ItchBrowseFilter(facet("tag-horror")).toPath())
    }

    @Test
    fun everyFacetStaysWithinTheTwoSegmentLimit() {
        ItchFacet.visible(true).forEach { entry ->
            val segments = ItchBrowseFilter(entry).toPath().removePrefix("games/").split("/").filter { it.isNotEmpty() }
            assertTrue("${entry.segment} produced ${segments.size} segments", segments.size <= 2)
            assertTrue("${entry.segment} lost the free filter", "free" in segments)
        }
    }

    @Test
    fun allIsTheDefaultFacetAndLeadsTheChipRow() {
        assertEquals(ItchFacet.ALL, ItchBrowseFilter().facet)
        assertEquals(ItchFacet.ALL, ItchFacet.visible(false).first())
        assertEquals(listOf(ItchFacet.ALL, ItchFacet.OWNED, ItchFacet.POPULAR), ItchFacet.visible(true).take(3))
        assertEquals(
            listOf(R.string.itch_facet_all, R.string.itch_facet_popular, R.string.itch_facet_new_and_popular),
            ItchFacet.visible(false).take(3).map { it.labelRes },
        )
    }

    @Test
    fun allBrowsesFreeGamesAndIsDistinctFromPopular() {
        assertEquals("games/free/platform-windows", ItchBrowseFilter(ItchFacet.ALL).toPath())
        assertTrue(ItchBrowseFilter(ItchFacet.ALL).isAll)
        assertFalse(ItchBrowseFilter(ItchFacet.POPULAR).isAll)
        assertFalse(ItchFacet.ALL == ItchFacet.POPULAR)
    }

    @Test
    fun gamepadFacetFollowsTheFreeSegment() {
        assertEquals("games/free/input-gamepad", ItchBrowseFilter(facet("input-gamepad")).toPath())
    }

    @Test
    fun ownedFacetIsFlaggedAndOnlyOfferedWhenSignedIn() {
        assertTrue(ItchBrowseFilter(ItchFacet.OWNED).isOwned)
        assertFalse(ItchBrowseFilter(ItchFacet.POPULAR).isOwned)
        assertTrue(ItchFacet.visible(true).contains(ItchFacet.OWNED))
        assertFalse(ItchFacet.visible(false).contains(ItchFacet.OWNED))
    }
}
