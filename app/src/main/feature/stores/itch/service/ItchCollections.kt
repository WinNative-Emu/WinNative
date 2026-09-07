package com.winlator.cmod.feature.stores.itch.service

import android.content.Context
import com.winlator.cmod.feature.stores.itch.data.ItchGame
import org.json.JSONObject
import timber.log.Timber

data class ItchCollection(
    val id: String,
    val title: String,
    val url: String,
)

data class ItchCollectionForm(
    val action: String,
    val hidden: Map<String, String>,
    val radioField: String,
    val options: List<Pair<String, String>>,
    val titleField: String,
)

object ItchCollections {
    const val DEFAULT_TITLE = "WinNative"

    private const val COLLECTIONS_URL = "https://itch.io/my-collections"

    private val formRegex = Regex("<form([^>]*)>(.*?)</form>", RegexOption.DOT_MATCHES_ALL)
    private val inputRegex = Regex("<input([^>]*)>")
    private val attrRegex = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*\"([^\"]*)\"")
    private val collectionLinkRegex = Regex("href=\"(https://itch\\.io/c/(\\d+)/[^\"]*)\"[^>]*>([^<]*)</a>")
    private val tagRegex = Regex("<[^>]+>")

    fun addGame(
        context: Context,
        game: ItchGame,
        title: String = DEFAULT_TITLE,
    ): Boolean {
        if (!ItchAuthManager.isLoggedIn(context)) return false
        return runCatching {
            val html = modalHtml(context, game.id)
            if (!ItchWebClient.isSignedIn(html)) {
                Timber.i("[Itch] collection modal returned a signed-out page")
                return false
            }
            val form = parseForm(html) ?: run {
                Timber.w("[Itch] collection form not recognised: %s", describe(html))
                return false
            }
            val existing = form.options.firstOrNull { it.second.equals(title, ignoreCase = true) }
            val fields = LinkedHashMap(form.hidden)
            if (existing != null) {
                fields[form.radioField] = existing.first
            } else {
                if (form.titleField.isEmpty()) {
                    Timber.w("[Itch] collection form has no title field: %s", form.options.size)
                    return false
                }
                form.options.firstOrNull { it.first.isBlank() || it.first == "0" }?.let { fields[form.radioField] = it.first }
                fields[form.titleField] = title
            }
            Timber.i("[Itch] collection form fields=%s options=%d", fields.keys, form.options.size)
            val endpoint = absolute(form.action, game.id)
            val response = ItchWebClient.postForm(context, endpoint, fields)
            val ok = !response.contains("\"errors\"")
            Timber.i("[Itch] filed '%s' into collection '%s': %s", game.title, title, ok)
            ok
        }.getOrElse {
            Timber.w(it, "[Itch] could not file ${game.title} into a collection")
            false
        }
    }

    fun list(context: Context): List<ItchCollection> {
        if (!ItchAuthManager.isLoggedIn(context)) return emptyList()
        val html = runCatching { ItchWebClient.getHtml(context, COLLECTIONS_URL) }.getOrNull() ?: return emptyList()
        if (!ItchWebClient.isSignedIn(html)) return emptyList()
        return collectionLinkRegex
            .findAll(html)
            .map { ItchCollection(it.groupValues[2], ItchCatalog.stripHtml(it.groupValues[3]).trim(), it.groupValues[1]) }
            .filter { it.title.isNotEmpty() }
            .distinctBy { it.id }
            .toList()
    }

    fun games(
        context: Context,
        collection: ItchCollection,
        page: Int,
    ): List<ItchGame> {
        val url = if (page <= 1) collection.url else "${collection.url}?page=$page"
        val html = runCatching { ItchWebClient.getHtml(context, url) }.getOrNull() ?: return emptyList()
        return ItchCatalog.parseGameCells(html)
    }

    fun parseForm(html: String): ItchCollectionForm? {
        val form = formRegex.find(html) ?: return null
        val attrs = attributes(form.groupValues[1])
        val body = form.groupValues[2]
        val inputs = inputRegex.findAll(body).map { attributes(it.groupValues[1]) }.toList()
        val hidden =
            inputs
                .filter { it["type"].equals("hidden", ignoreCase = true) }
                .mapNotNull { input -> input["name"]?.let { it to input["value"].orEmpty() } }
                .toMap()
        val radioMatches = inputRegex.findAll(body).filter { it.value.contains("radio") }.toList()
        val radioField =
            radioMatches.firstNotNullOfOrNull { attributes(it.groupValues[1])["name"] } ?: return null
        val options =
            radioMatches.mapIndexed { index, match ->
                val end = if (index + 1 < radioMatches.size) radioMatches[index + 1].range.first else body.length
                attributes(match.groupValues[1])["value"].orEmpty() to text(body.substring(match.range.last + 1, end))
            }
        val titleField =
            inputs
                .firstOrNull { it["type"].equals("text", ignoreCase = true) && !it["name"].isNullOrBlank() }
                ?.get("name")
                .orEmpty()
        return ItchCollectionForm(attrs["action"].orEmpty(), hidden, radioField, options, titleField)
    }

    private fun modalHtml(
        context: Context,
        gameId: Int,
    ): String {
        val body = ItchWebClient.getHtml(context, "https://itch.io/game/collections/$gameId")
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{")) return body
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return body
        return listOf("content", "html", "lightbox")
            .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
            ?: body
    }

    private fun absolute(
        action: String,
        gameId: Int,
    ): String =
        when {
            action.startsWith("http") -> action
            action.startsWith("/") -> "https://itch.io$action"
            action.isBlank() -> "https://itch.io/game/collections/$gameId"
            else -> "https://itch.io/$action"
        }

    private fun attributes(raw: String): Map<String, String> =
        attrRegex.findAll(raw).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    private fun text(raw: String): String = ItchCatalog.stripHtml(tagRegex.replace(raw, " ")).trim()

    private fun describe(html: String): String {
        val forms = formRegex.findAll(html).count()
        val radios = inputRegex.findAll(html).count { it.value.contains("radio") }
        return "len=${html.length} forms=$forms radios=$radios"
    }
}
