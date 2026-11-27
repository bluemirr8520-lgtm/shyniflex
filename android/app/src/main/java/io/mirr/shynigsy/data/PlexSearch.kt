package io.mirr.shynigsy.data

import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

private val personFilters = setOf("actor", "director")
private const val SEARCH_RESULT_LIMIT = 200
private const val PERSON_HUB_LIMIT = 6
private const val PERSON_WORK_LIMIT = 40

private fun JSONObject.searchRows(key: String): List<JSONObject> = optJSONArray(key)?.let { array ->
    (0 until array.length()).mapNotNull(array::optJSONObject)
}.orEmpty()

private fun searchName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

private data class PlexSearchHit(val row: JSONObject, val item: MediaItem, val personMatch: Boolean)
private data class PlexPersonFilter(val libraryId: Int, val kind: String, val id: Int, val type: String?)
private data class PlexPersonExpansion(val hits: List<PlexSearchHit>, val failedLibrary: Int? = null)
internal data class PlexLibraryPersonSearch(val path: String?, val incomplete: Boolean = false)

/** Account/server isolation comes from the immutable owning PlexClient; cache only completed lookups. */
internal class PlexPersonSearchCache(private val nanoTime: () -> Long = System::nanoTime) {
    private data class Entry(val path: String?, val created: Long)
    private val entries = linkedMapOf<Triple<Int, String, String>, Entry>()
    @Synchronized fun get(library: Library, query: String): Pair<Boolean, String?> {
        val key = Triple(library.id, library.type, query)
        val value = entries[key] ?: return false to null
        if (nanoTime() - value.created >= 300_000_000_000L) {
            entries.remove(key)
            return false to null
        }
        return true to value.path
    }
    @Synchronized fun put(library: Library, query: String, path: String?) {
        val key = Triple(library.id, library.type, query)
        entries.remove(key)
        entries[key] = Entry(path, nanoTime())
        while (entries.size > 16) entries.remove(entries.keys.first())
    }
}

private fun PlexClient.personFilter(hub: JSONObject, row: JSONObject, scope: Map<Int, Library>?): PlexPersonFilter? {
    val kind = hub.stringOrNull("type")?.takeIf { it in personFilters }
        ?: hub.stringOrNull("hubIdentifier")?.takeIf { it in personFilters } ?: return null
    if (row.stringOrNull("tag") == null && row.stringOrNull("title") == null) return null
    val url = resource(row.stringOrNull("key")) ?: return null
    val localSections = requireNotNull(resource("/library/sections/")).encodedPath
    val relative = url.encodedPath.removePrefix(localSections)
    if (relative == url.encodedPath || !Regex("[1-9][0-9]*/all").matches(relative)) return null
    val libraryId = relative.substringBefore('/').toIntOrNull() ?: return null
    if (scope != null && libraryId !in scope) return null
    if (listOf(row, hub).any { it.has("librarySectionID") && it.intOrNull("librarySectionID") != libraryId }) return null
    val id = url.queryParameterValues(kind).singleOrNull()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    if (row.has("id") && row.intOrNull("id") != id) return null
    if (personFilters.any { it != kind && it in url.queryParameterNames }) return null
    val type = scope?.get(libraryId)?.let { if (it.type == "movie") "1" else "2" }
        ?: row.stringOrNull("librarySectionType") ?: url.queryParameter("type")
    if (type != null && type !in setOf("1", "2")) return null
    return PlexPersonFilter(libraryId, kind, id, type)
}

/** The title/person OR group is followed by the existing type/watch/sort/page parameters. */
internal suspend fun PlexClient.libraryPersonSearchPath(library: Library, query: String): PlexLibraryPersonSearch {
    val cached = personSearchCache.get(library, query)
    if (cached.first) return PlexLibraryPersonSearch(cached.second)
    val container = try {
        withTimeoutOrNull(8_000) {
            request("/hubs/search", mapOf("query" to query, "sectionId" to library.id.toString(), "limit" to "40"))
        }
    } catch (error: IOException) {
        if (error is PlexRequestException && error.status in setOf(401, 403)) throw error
        if (error is PlexRequestException && error.status == 404) {
            currentCoroutineContext().ensureActive()
            personSearchCache.put(library, query, null)
            return PlexLibraryPersonSearch(null)
        }
        null
    } ?: return PlexLibraryPersonSearch(null, incomplete = true)
    val scope = mapOf(library.id to library)
    val people = container.searchRows("Hub").flatMap { hub ->
        (hub.searchRows("Directory") + hub.searchRows("Metadata")).mapNotNull { personFilter(hub, it, scope) }
    }.distinct().take(80)
    val path = if (people.isEmpty()) null else requireNotNull(resource("/library/sections/${library.id}/all"))
        .newBuilder().addQueryParameter("push", "1").addQueryParameter("title", query).apply {
            for ((kind, matches) in people.groupBy { it.kind }) {
                addQueryParameter("or", "1")
                addQueryParameter(kind, matches.map { it.id }.distinct().joinToString(","))
            }
            addQueryParameter("pop", "1")
        }.build().toString()
    currentCoroutineContext().ensureActive()
    personSearchCache.put(library, query, path)
    return PlexLibraryPersonSearch(path)
}

/** PMS resolves localized names, aliases and spelling; a name string is never a local person ID. */
internal suspend fun PlexClient.searchResults(container: JSONObject, query: String,
    libraries: List<Library>?): LibrarySearchResult {
    val scope = libraries?.filter { it.type in setOf("movie", "tv", "show") }?.associateBy { it.id }
    val localMetadata = requireNotNull(resource("/library/metadata/")).encodedPath
    val needle = searchName(query)
    val terms = query.split(Regex("[^\\p{L}\\p{N}]+")).map(::searchName).filter(String::isNotEmpty)
    fun titleRank(row: JSONObject): Int {
        val titles = listOf("title", "originalTitle", "titleSort").mapNotNull(row::stringOrNull).map(::searchName)
        if (needle.isEmpty()) return 4
        return when {
            titles.any { it == needle } -> 0
            titles.any { it.startsWith(needle) } -> 1
            titles.any { it.contains(needle) } -> 2
            terms.size > 1 && titles.any { title -> terms.all { title.contains(it) } } -> 3
            else -> 4
        }
    }
    fun parseHit(row: JSONObject, libraryId: Int, personMatch: Boolean = false): PlexSearchHit? {
        val key = row.stringOrNull("key")
        if (key != null && resource(key)?.encodedPath?.startsWith(localMetadata) != true) return null
        if (row.has("librarySectionID") && row.intOrNull("librarySectionID") == null) return null
        if (libraryId > 0 && row.has("librarySectionID") && row.intOrNull("librarySectionID") != libraryId) return null
        val item = mediaItem(row, libraryId) ?: return null
        if (!item.playable && item.type != "show") return null
        if (scope != null && item.libraryId != 0 && item.libraryId !in scope) return null
        // The documented reason identifies media that matched a person, rather than the title.
        val reason = row.stringOrNull("reason")
        val relatedPerson = reason in personFilters && row.intOrNull("reasonID") != null
        return PlexSearchHit(row, item, personMatch || relatedPerson)
    }
    val hits = mutableListOf<PlexSearchHit>()
    val people = linkedSetOf<PlexPersonFilter>()
    for (hub in container.searchRows("Hub").ifEmpty { listOf(container) }) {
        if (hub.has("librarySectionID") && hub.intOrNull("librarySectionID") == null) continue
        val rows = hub.searchRows("Metadata") + hub.searchRows("Directory") + hub.searchRows("Video")
        for (row in rows) {
            parseHit(row, hub.optInt("librarySectionID"))?.let(hits::add)
            personFilter(hub, row, scope)?.let(people::add)
        }
    }
    // Follow only the local library/person identity provided by PMS. Rebuild the read-only
    // filter instead of following arbitrary URL parameters, tokens or external profile links.
    // Search stays bounded like /hubs/search; this does not enumerate an entire library.
    val expansions = coroutineScope {
        val slots = Semaphore(2)
        people.take(PERSON_HUB_LIMIT).map { person ->
            async {
                slots.withPermit {
                    currentCoroutineContext().ensureActive()
                    val parameters = mutableMapOf(person.kind to person.id.toString(), "includeCollections" to "0",
                        "X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to PERSON_WORK_LIMIT.toString())
                    person.type?.let { parameters["type"] = it }
                    val works = try {
                        withTimeoutOrNull(8_000) { request("/library/sections/${person.libraryId}/all", parameters) }
                    } catch (error: IOException) {
                        if (error is PlexRequestException && error.status in setOf(401, 403)) throw error
                        null
                    }
                    if (works == null) return@withPermit PlexPersonExpansion(emptyList(), person.libraryId)
                    val found = mutableListOf<PlexSearchHit>()
                    for (row in works.searchRows("Metadata").take(PERSON_WORK_LIMIT)) {
                        currentCoroutineContext().ensureActive()
                        val hit = parseHit(row, person.libraryId, personMatch = true) ?: continue
                        if (hit.item.libraryId != person.libraryId || hit.item.type !in setOf("movie", "show")) continue
                        if (person.type == "1" && hit.item.type != "movie" || person.type == "2" && hit.item.type != "show") continue
                        found += hit
                    }
                    PlexPersonExpansion(found)
                }
            }
        }.awaitAll()
    }
    expansions.forEach { hits += it.hits }
    val ranked = hits.map { it to titleRank(it.row) }
    val evidenced = ranked.filter { it.second < 4 || it.first.personMatch }
    // Keep Plex's alias/spelling results when there is no direct evidence. A title match
    // never removes a verified person result, including translated/fuzzy person hub names.
    val items = (if (evidenced.isEmpty()) ranked else evidenced)
        .sortedBy { it.second }.map { it.first.item }.distinctBy { it.id }.take(SEARCH_RESULT_LIMIT)
    return LibrarySearchResult(items, expansions.mapNotNull { it.failedLibrary }.distinct().size)
}
