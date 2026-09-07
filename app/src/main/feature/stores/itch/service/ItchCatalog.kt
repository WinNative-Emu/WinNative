package com.winlator.cmod.feature.stores.itch.service

import android.content.Context
import androidx.core.text.HtmlCompat
import com.winlator.cmod.feature.stores.itch.data.ItchBrowseFilter
import com.winlator.cmod.feature.stores.itch.data.ItchGame
import com.winlator.cmod.feature.stores.itch.data.ItchGameDetails
import com.winlator.cmod.feature.stores.itch.data.ItchInput
import com.winlator.cmod.feature.stores.itch.data.ItchPlatform
import com.winlator.cmod.feature.stores.itch.data.ItchUpload
import org.json.JSONObject
import timber.log.Timber
import java.util.Locale

object ItchCatalog {
    private val cellOpenRegex = Regex("<div[^>]*data-game_id=\"(\\d+)\"[^>]*>")
    private val anchorTagRegex = Regex("<a([^>]*)>([^<]*)</a>")
    private val hrefRegex = Regex("href=\"([^\"]+)\"")
    private val imgRegex = Regex("<img[^>]*>")
    private val lazySrcRegex = Regex("data-lazy_src=\"([^\"]+)\"")
    private val srcRegex = Regex("src=\"([^\"]+)\"")
    private val authorBlockRegex = Regex("<div class=\"game_author\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
    private val anchorTextRegex = Regex("<a[^>]*>([^<]*)</a>")
    private val textDivRegex = Regex("<div([^>]*)>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)

    private val priceRegex = Regex("class=\"price_value\"[^>]*>([^<]*)")
    private val uploadIdRegex = Regex("data-upload_id=\"(\\d+)\"")
    private val strongTagRegex = Regex("<strong([^>]*)>")
    private val uploadSizeRegex = Regex("class=\"file_size\"><span>([^<]*)</span>")
    private val uploadVersionRegex = Regex("class=\"version_name\">([^<]*)</span>")
    private val uploadDateRegex = Regex("class=\"upload_date\">.*?<abbr[^>]*title=\"([^\"]*)\"", RegexOption.DOT_MATCHES_ALL)
    private val downloadForRegex = Regex("title=\"Download for ([^\"]+)\"")
    private val screenshotRegex = Regex("<img[^>]*class=\"screenshot\"[^>]*>")
    private val metaTagRegex = Regex("<meta([^>]*)>")
    private val infoRowRegex = Regex("<td>([^<]{0,40})</td>\\s*<td>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
    private val infoLinkRegex = Regex("<a[^>]*href=\"[^\"]*/games/([a-z0-9-]+)\"[^>]*>([^<]+)</a>")
    private val sizeRegex = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(B|KB|MB|GB|TB)", RegexOption.IGNORE_CASE)

    fun browse(
        context: Context,
        filter: ItchBrowseFilter,
        page: Int,
    ): List<ItchGame> {
        val body = ItchWebClient.getHtml(context, ItchConstants.browseUrl(filter.toPath(), page))
        val content =
            runCatching { JSONObject(body).optString("content") }.getOrNull()
                ?: return parseGameCells(body)
        return parseGameCells(content)
    }

    fun search(
        context: Context,
        query: String,
    ): List<ItchGame> {
        val body = ItchWebClient.getHtml(context, ItchConstants.searchUrl(query))
        return parseGameCells(body)
    }

    fun details(
        context: Context,
        game: ItchGame,
    ): ItchGameDetails {
        val html = ItchWebClient.getHtml(context, game.url.trimEnd('/'))
        return parseDetails(html, game)
    }

    fun parseGameCells(html: String): List<ItchGame> {
        val matches = cellOpenRegex.findAll(html).filter { it.value.contains("game_cell") }.toList()
        val games = LinkedHashMap<Int, ItchGame>()
        matches.forEachIndexed { index, match ->
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else html.length
            val cell = html.substring(match.range.first, end)
            val id = match.groupValues[1].toIntOrNull() ?: return@forEachIndexed
            val anchor =
                anchorTagRegex
                    .findAll(cell)
                    .firstOrNull { match ->
                        val classes = attr(match.groupValues[1], "class").orEmpty()
                        classes.contains("game_link") && classes.contains("title")
                    } ?: return@forEachIndexed
            val url = hrefRegex.find(anchor.groupValues[1])?.groupValues?.get(1)?.let(::decode) ?: return@forEachIndexed
            val title = decode(anchor.groupValues[2]).trim()
            if (title.isEmpty()) return@forEachIndexed
            games[id] =
                ItchGame(
                    id = id,
                    title = title,
                    url = url,
                    coverUrl = coverOf(cell),
                    author = authorOf(cell),
                    shortText = shortTextOf(cell),
                    genre = divTextOf(cell, "game_genre"),
                    priceLabel = priceRegex.find(cell)?.groupValues?.get(1)?.let(::decode)?.trim().orEmpty(),
                    onSale = cell.contains("price_tag meta_tag sale"),
                    platforms = platformsOf(divBlockOf(cell, "game_platform")),
                )
        }
        return games.values.toList()
    }

    fun parseDetails(
        html: String,
        game: ItchGame,
    ): ItchGameDetails {
        val infoRows =
            infoRowRegex
                .findAll(html)
                .map { decode(it.groupValues[1]).trim() to stripHtml(it.groupValues[2]).trim() }
                .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
                .take(12)
                .toList()
        val description =
            extractDivBlock(html, "formatted_description")?.let(::stripHtml)?.trim().orEmpty().ifBlank {
                metaContent(html, "name", "description").orEmpty()
            }
        val screenshots =
            screenshotRegex
                .findAll(html)
                .mapNotNull { srcRegex.find(it.value)?.groupValues?.get(1)?.let(::decode) }
                .distinct()
                .take(8)
                .toList()
        val minPrice = viewGameProps(html)?.optJSONObject("game")?.let { if (it.has("min_price")) it.optInt("min_price") else null }
        val tags =
            (infoLinks(html, "Tags") + infoLinks(html, "Genre"))
                .map { it.label }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(12)
        return ItchGameDetails(
            game = game,
            heroImageUrl = metaContent(html, "property", "og:image").orEmpty(),
            description = description.take(4000),
            screenshots = screenshots,
            tags = tags,
            inputs = infoLinks(html, "Inputs"),
            infoRows = infoRows,
            minPriceCents = minPrice,
        )
    }

    fun infoLinks(
        html: String,
        label: String,
    ): List<ItchInput> {
        val cell = infoCell(html, label) ?: return emptyList()
        return infoLinkRegex
            .findAll(cell)
            .map { ItchInput(it.groupValues[1], decode(it.groupValues[2]).trim()) }
            .filter { it.label.isNotEmpty() }
            .distinctBy { it.slug }
            .toList()
    }

    private fun infoCell(
        html: String,
        label: String,
    ): String? {
        val marker = "<td>$label</td>"
        val start = html.indexOf(marker)
        if (start < 0) return null
        val valueStart = html.indexOf("<td>", start + marker.length)
        if (valueStart < 0) return null
        val valueEnd = html.indexOf("</td>", valueStart)
        if (valueEnd < 0) return null
        return html.substring(valueStart, valueEnd)
    }

    fun parseUploads(html: String): List<ItchUpload> {
        val anchors = uploadIdRegex.findAll(html).toList()
        val uploads = LinkedHashMap<Long, ItchUpload>()
        anchors.forEachIndexed { index, match ->
            val end = if (index + 1 < anchors.size) anchors[index + 1].range.first else html.length
            val block = html.substring(match.range.first, end)
            val id = match.groupValues[1].toLongOrNull() ?: return@forEachIndexed
            if (uploads.containsKey(id)) return@forEachIndexed
            val name = uploadNameOf(block)
            if (name.isEmpty()) return@forEachIndexed
            val sizeLabel = uploadSizeRegex.find(block)?.groupValues?.get(1)?.let(::decode)?.trim().orEmpty()
            uploads[id] =
                ItchUpload(
                    id = id,
                    fileName = name,
                    sizeLabel = sizeLabel,
                    sizeBytes = parseSize(sizeLabel),
                    version = uploadVersionRegex.find(block)?.groupValues?.get(1)?.let(::decode)?.trim().orEmpty(),
                    uploadedAt = uploadDateRegex.find(block)?.groupValues?.get(1)?.let(::decode)?.trim().orEmpty(),
                    platforms =
                        downloadForRegex
                            .findAll(block)
                            .mapNotNull { platformOfLabel(it.groupValues[1]) }
                            .toSet(),
                )
        }
        return uploads.values.toList()
    }

    private fun uploadNameOf(block: String): String {
        val named =
            strongTagRegex
                .findAll(block)
                .firstOrNull { it.groupValues[1].contains("class=\"name\"") }
        val title = named?.let { attr(it.groupValues[1], "title") }
        if (!title.isNullOrBlank()) return decode(title).trim()
        val inner = named?.let { block.substring(it.range.last + 1).substringBefore("</strong>") }
        return inner?.let(::decode)?.trim().orEmpty()
    }

    private fun attr(
        attributes: String,
        name: String,
    ): String? = Regex("\\b$name=\"([^\"]*)\"").find(attributes)?.groupValues?.get(1)

    private fun metaContent(
        html: String,
        keyName: String,
        keyValue: String,
    ): String? =
        metaTagRegex
            .findAll(html)
            .firstOrNull { attr(it.groupValues[1], keyName) == keyValue }
            ?.let { attr(it.groupValues[1], "content") }
            ?.let(::decode)

    private fun authorOf(cell: String): String {
        val block = authorBlockRegex.find(cell)?.groupValues?.get(1) ?: return ""
        return anchorTextRegex.find(block)?.groupValues?.get(1)?.let(::decode)?.trim().orEmpty()
    }

    private fun shortTextOf(cell: String): String = stripHtml(divBlockOf(cell, "game_text")).trim()

    private fun divTextOf(
        cell: String,
        className: String,
    ): String = decode(divBlockOf(cell, className)).trim()

    private fun divBlockOf(
        cell: String,
        className: String,
    ): String =
        textDivRegex
            .findAll(cell)
            .firstOrNull { attr(it.groupValues[1], "class")?.split(' ')?.contains(className) == true }
            ?.groupValues
            ?.get(2)
            .orEmpty()

    fun pickWindowsUpload(uploads: List<ItchUpload>): ItchUpload? {
        if (uploads.isEmpty()) return null
        val windows = uploads.filter { ItchPlatform.WINDOWS in it.platforms }
        val pool = windows.ifEmpty { uploads.filter { it.platforms.isEmpty() } }.ifEmpty { uploads }
        return pool.maxByOrNull { scoreUpload(it) }
    }

    private fun scoreUpload(upload: ItchUpload): Long {
        val name = upload.fileName.lowercase(Locale.ROOT)
        var score = 0L
        if (ItchPlatform.WINDOWS in upload.platforms) score += 1_000_000
        if (name.endsWith(".zip") || name.endsWith(".7z")) score += 200_000
        if (name.endsWith(".exe")) score += 120_000
        if (name.endsWith(".rar")) score -= 300_000
        if (Regex("(^|[^a-z])(win|windows|pc)([^a-z]|$)").containsMatchIn(name)) score += 80_000
        if (Regex("(x64|win64|64bit|64-bit)").containsMatchIn(name)) score += 40_000
        if (Regex("(soundtrack|ost|artbook|wallpaper|manual|source|assets|sdk)").containsMatchIn(name)) score -= 500_000
        if (Regex("(demo|beta|alpha)").containsMatchIn(name)) score -= 20_000
        return score + (upload.sizeBytes / 1_000_000L).coerceAtMost(5_000L)
    }

    fun viewGameProps(html: String): JSONObject? {
        val marker = html.indexOf("init_ViewGame(")
        if (marker < 0) return null
        val brace = html.indexOf('{', marker)
        if (brace < 0) return null
        val json = ItchWebClient.extractJsonObject(html, brace) ?: return null
        return runCatching { JSONObject(json) }.getOrNull()
    }

    private fun coverOf(cell: String): String {
        val img = imgRegex.find(cell)?.value ?: return ""
        val lazy = lazySrcRegex.find(img)?.groupValues?.get(1)
        val src = srcRegex.find(img)?.groupValues?.get(1)
        return decode(lazy ?: src ?: "")
    }

    private fun platformsOf(block: String): Set<ItchPlatform> {
        val platforms = mutableSetOf<ItchPlatform>()
        if (block.contains("web_flag")) platforms.add(ItchPlatform.WEB)
        if (block.contains("icon-windows8")) platforms.add(ItchPlatform.WINDOWS)
        if (block.contains("icon-tux")) platforms.add(ItchPlatform.LINUX)
        if (block.contains("icon-apple")) platforms.add(ItchPlatform.MACOS)
        if (block.contains("icon-android")) platforms.add(ItchPlatform.ANDROID)
        return platforms
    }

    private fun platformOfLabel(label: String): ItchPlatform? =
        when {
            label.startsWith("Windows", ignoreCase = true) -> ItchPlatform.WINDOWS
            label.startsWith("Linux", ignoreCase = true) -> ItchPlatform.LINUX
            label.startsWith("macOS", ignoreCase = true) || label.startsWith("Mac", ignoreCase = true) -> ItchPlatform.MACOS
            label.startsWith("Android", ignoreCase = true) -> ItchPlatform.ANDROID
            else -> null
        }

    fun parseSize(label: String): Long {
        val match = sizeRegex.find(label) ?: return 0L
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0L
        val multiplier =
            when (match.groupValues[2].uppercase(Locale.ROOT)) {
                "B" -> 1L
                "KB" -> 1024L
                "MB" -> 1024L * 1024L
                "GB" -> 1024L * 1024L * 1024L
                else -> 1024L * 1024L * 1024L * 1024L
            }
        return (value * multiplier).toLong()
    }

    private fun extractDivBlock(
        html: String,
        className: String,
    ): String? {
        val marker = Regex("<div[^>]*class=\"[^\"]*$className[^\"]*\"[^>]*>").find(html) ?: return null
        var index = marker.range.last + 1
        var depth = 1
        val start = index
        while (index < html.length && depth > 0) {
            val nextOpen = html.indexOf("<div", index)
            val nextClose = html.indexOf("</div", index)
            if (nextClose < 0) return null
            if (nextOpen in 0 until nextClose) {
                depth++
                index = nextOpen + 4
            } else {
                depth--
                if (depth == 0) return html.substring(start, nextClose)
                index = nextClose + 5
            }
        }
        return null
    }

    fun stripHtml(value: String): String =
        runCatching {
            HtmlCompat
                .fromHtml(value.replace(Regex("<(br|/p|/div|/li)[^>]*>", RegexOption.IGNORE_CASE), "\n"), HtmlCompat.FROM_HTML_MODE_COMPACT)
                .toString()
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }.getOrElse {
            Timber.d("[Itch] html strip fallback")
            value.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        }

    private fun decode(value: String): String =
        if (value.contains('&')) {
            runCatching { HtmlCompat.fromHtml(value, HtmlCompat.FROM_HTML_MODE_COMPACT).toString() }.getOrDefault(value)
        } else {
            value
        }
}
