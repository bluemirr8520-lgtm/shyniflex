package io.mirr.shynigsy.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

private const val ACTOR_CHOICE_LIMIT = 20_000
private const val MATCHED_ACTOR_LIMIT = 80

internal fun plexSearchName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

internal fun JSONObject.plexSearchId(key: String): Int? = opt(key)?.takeIf { it is String || it is Number }
    ?.toString()?.takeIf { Regex("[1-9][0-9]*").matches(it) }?.toIntOrNull()

private fun JSONObject.countValue(key: String): Int? = opt(key)?.takeIf { it is String || it is Number }
    ?.toString()?.takeIf { Regex("0|[1-9][0-9]*").matches(it) }?.toIntOrNull()

internal data class PlexActorChoice(val id: Int, val name: String)
internal data class PlexActorMatches(val ids: List<Int>, val incomplete: Boolean = false)

/** Small, account/server-local facet cache. Media catalogues are never loaded here. */
internal class PlexActorChoiceCache(private val nanoTime: () -> Long = System::nanoTime) {
    private data class Entry(val choices: List<PlexActorChoice>, val created: Long)
    private val entries = linkedMapOf<Pair<Int, String>, Entry>()
    @Synchronized fun get(library: Library): List<PlexActorChoice>? {
        val key = library.id to library.type
        val entry = entries[key] ?: return null
        if (nanoTime() - entry.created >= 300_000_000_000L) {
            entries.remove(key)
            return null
        }
        return entry.choices
    }
    @Synchronized fun put(library: Library, choices: List<PlexActorChoice>) {
        val key = library.id to library.type
        entries.remove(key)
        entries[key] = Entry(choices.toList(), nanoTime())
        while (entries.size > 16) entries.remove(entries.keys.first())
    }
}

/** Numeric facet keys belong to this requested section; never treat a name as an identity. */
internal suspend fun PlexClient.actorMatches(library: Library, query: String): PlexActorMatches {
    val needle = plexSearchName(query)
    if (needle.isEmpty() || library.id <= 0 || library.type !in setOf("movie", "tv", "show"))
        return PlexActorMatches(emptyList())
    var incomplete = false
    val choices = actorChoiceCache.get(library) ?: run {
        val container = request("/library/sections/${library.id}/actor", maximumResponseBytes = 4L * 1024 * 1024)
        if (container.has("librarySectionID") && container.plexSearchId("librarySectionID") != library.id)
            return PlexActorMatches(emptyList(), incomplete = true)
        val rows = container.optJSONArray("Directory")
        if (rows == null && (container.has("Directory") || container.countValue("size") != 0))
            return PlexActorMatches(emptyList(), incomplete = true)
        val rowCount = rows?.length() ?: 0
        incomplete = rowCount > ACTOR_CHOICE_LIMIT ||
            (container.has("offset") && container.countValue("offset") != 0) ||
            (container.has("size") && container.countValue("size") != rowCount) ||
            (container.has("totalSize") && container.countValue("totalSize") != rowCount)
        val parsed = mutableListOf<PlexActorChoice>()
        for (index in 0 until minOf(rowCount, ACTOR_CHOICE_LIMIT)) {
            currentCoroutineContext().ensureActive()
            val row = rows?.optJSONObject(index)
            val key = row?.opt("key") as? String
            val id = key?.takeIf { Regex("[1-9][0-9]*").matches(it) }?.toIntOrNull()
            val name = (row?.opt("title") as? String)?.let(::plexSearchName)?.takeIf(String::isNotEmpty)
            if (row == null || id == null || name == null ||
                (row.has("librarySectionID") && row.plexSearchId("librarySectionID") != library.id) ||
                (row.has("id") && row.plexSearchId("id") != id)) { incomplete = true; continue }
            // fastKey is not followed. When present, reject contradictory section/actor identities.
            if (row.has("fastKey")) {
                val fast = resource(row.stringOrNull("fastKey"))
                val expectedPath = requireNotNull(resource("/library/sections/${library.id}/all")).encodedPath
                if (fast?.encodedPath != expectedPath || fast.queryParameterValues("actor") != listOf(id.toString())) {
                    incomplete = true
                    continue
                }
            }
            parsed += PlexActorChoice(id, name)
        }
        currentCoroutineContext().ensureActive()
        parsed.distinct().also { if (!incomplete) actorChoiceCache.put(library, it) }
    }
    val ids = choices.asSequence().filter { it.name.contains(needle) }.map { it.id }.distinct().toList()
    return PlexActorMatches(ids.take(MATCHED_ACTOR_LIMIT), incomplete || ids.size > MATCHED_ACTOR_LIMIT)
}
