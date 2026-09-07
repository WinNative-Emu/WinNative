package com.winlator.cmod.feature.stores.itch

import com.winlator.cmod.feature.stores.itch.service.ItchCollections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItchCollectionsTest {
    private val modal =
        """
        <div class="collection_lightbox_widget">
        <form action="/game/collections/3609548" method="post">
        <input type="hidden" name="csrf_token" value="abc123"/>
        <ul>
        <li><label class="collection_option"><input type="radio" name="collection_id" value="88121"/> Favourites</label></li>
        <li><label class="collection_option"><input type="radio" name="collection_id" value="90455"/> WinNative</label></li>
        <li><label class="collection_option"><input type="radio" name="collection_id" value=""/> New collection
        <input type="text" name="collection_title" class="collection_input" placeholder="Collection name"/></label></li>
        </ul>
        <button type="submit">Add</button>
        </form></div>
        """.trimIndent()

    private val divWrappedModal =
        """
        <form method="post" action="https://itch.io/game/collections/1">
        <input name="csrf_token" type="hidden" value="zz"/>
        <div class="collection_option"><input value="12" name="collection_id" type="radio"/><span>Wishlist</span></div>
        <div class="collection_option"><input value="" name="collection_id" type="radio"/><span>New</span>
        <input name="title" type="text"/></div>
        </form>
        """.trimIndent()

    @Test
    fun readsCollectionOptionsAndCsrfFromTheModal() {
        val form = ItchCollections.parseForm(modal)!!
        assertEquals("/game/collections/3609548", form.action)
        assertEquals("abc123", form.hidden["csrf_token"])
        assertEquals("collection_id", form.radioField)
        assertEquals("collection_title", form.titleField)
        assertEquals(listOf("88121", "90455", ""), form.options.map { it.first })
        assertTrue(form.options.any { it.second.startsWith("WinNative") })
        assertEquals("90455", form.options.first { it.second.startsWith("WinNative") }.first)
    }

    @Test
    fun readsOptionsRegardlessOfWrapperElementOrAttributeOrder() {
        val form = ItchCollections.parseForm(divWrappedModal)!!
        assertEquals("https://itch.io/game/collections/1", form.action)
        assertEquals("collection_id", form.radioField)
        assertEquals("title", form.titleField)
        assertEquals(listOf("12", ""), form.options.map { it.first })
        assertEquals("Wishlist", form.options[0].second)
    }

    @Test
    fun rejectsAPageWithNoCollectionForm() {
        assertNull(ItchCollections.parseForm("<div class=\"login_form\"><form action=\"/login\"></form></div>"))
    }
}
