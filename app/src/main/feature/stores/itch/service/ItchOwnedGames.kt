package com.winlator.cmod.feature.stores.itch.service

import android.content.Context
import com.winlator.cmod.feature.stores.itch.data.ItchGame
import timber.log.Timber

object ItchOwnedGames {
    private const val PURCHASES_URL = "https://itch.io/my-purchases"
    private const val ANCHOR_WINDOW = 700

    private val anchorRegex = Regex("<a([^>]*)>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val hrefRegex = Regex("href=\"([^\"]*)\"")
    private val gameUrlRegex = Regex("https://([a-z0-9][a-z0-9_-]*)\\.itch\\.io/([a-z0-9][a-z0-9_.-]*)/?")
    private val gameIdRegex = Regex("data-game_id=\"(\\d+)\"")
    private val imgRegex = Regex("<img[^>]*>")
    private val srcRegex = Regex("(?:data-lazy_src|src)=\"([^\"]+)\"")

    fun fetch(
        context: Context,
        page: Int,
    ): List<ItchGame> {
        val url = if (page <= 1) PURCHASES_URL else "$PURCHASES_URL?page=$page"
        val html = ItchWebClient.getHtml(context, url)
        if (!ItchWebClient.isSignedIn(html)) {
            Timber.i("[Itch] owned library requested while signed out")
            return emptyList()
        }
        val cells = ItchCatalog.parseGameCells(html)
        val purchased = cells.ifEmpty { parseRows(html) }
        val filed = filedGames(context, page)
        val games = LinkedHashMap<String, ItchGame>()
        (purchased + filed).forEach { game -> games.putIfAbsent(game.url, game) }
        Timber.i("[Itch] owned page %d: %d purchased, %d filed", page, purchased.size, filed.size)
        return games.values.toList()
    }

    private fun filedGames(
        context: Context,
        page: Int,
    ): List<ItchGame> {
        val collection =
            ItchCollections
                .list(context)
                .firstOrNull { it.title.equals(ItchCollections.DEFAULT_TITLE, ignoreCase = true) }
                ?: return emptyList()
        return ItchCollections.games(context, collection, page)
    }

    fun parseRows(html: String): List<ItchGame> {
        val body = html.substringAfter("<div class=\"main\"", html)
        val markers = gameIdRegex.findAll(body).toList()
        val games = LinkedHashMap<String, ItchGame>()
        if (markers.isNotEmpty()) {
            markers.forEachIndexed { index, marker ->
                val end = if (index + 1 < markers.size) markers[index + 1].range.first else body.length
                val block = body.substring(marker.range.first, end)
                val id = marker.groupValues[1].toIntOrNull() ?: return@forEachIndexed
                gameIn(block)?.let { game -> games.putIfAbsent(game.url, game.copy(id = id)) }
            }
        } else {
            anchorRegex.findAll(body).forEach { match ->
                val window =
                    body.substring(
                        (match.range.first - ANCHOR_WINDOW).coerceAtLeast(0),
                        (match.range.last + ANCHOR_WINDOW).coerceAtMost(body.length),
                    )
                gameFromAnchor(match.groupValues[1], match.groupValues[2], window)?.let { games.putIfAbsent(it.url, it) }
            }
        }
        return games.values.toList()
    }

    private fun gameIn(block: String): ItchGame? =
        anchorRegex
            .findAll(block)
            .firstNotNullOfOrNull { gameFromAnchor(it.groupValues[1], it.groupValues[2], block) }

    private fun gameFromAnchor(
        attributes: String,
        inner: String,
        coverScope: String,
    ): ItchGame? {
        val href = hrefRegex.find(attributes)?.groupValues?.get(1)?.trim() ?: return null
        val match = gameUrlRegex.matchEntire(href) ?: return null
        val title = ItchCatalog.stripHtml(inner).trim()
        if (title.isEmpty() || title.length > 120) return null
        val url = href.trimEnd('/')
        return ItchGame(
            id = syntheticId(url),
            title = title,
            url = url,
            coverUrl = coverIn(coverScope),
            author = match.groupValues[1],
        )
    }

    private fun coverIn(block: String): String {
        val img = imgRegex.findAll(block).firstOrNull { it.value.contains("img.itch.zone") } ?: return ""
        return srcRegex.find(img.value)?.groupValues?.get(1).orEmpty()
    }

    private fun syntheticId(url: String): Int = -((url.hashCode() and Int.MAX_VALUE) % 1_000_000_000 + 1)
}
