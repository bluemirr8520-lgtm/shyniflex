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
private const val EXCLUDED_SECTION_LIMIT = 8

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
    return withTimeoutOrNull(8_000) {
        var incomplete = false
        val container = if (library.id in excludedSearchSections) null else try {
            request("/hubs/search", mapOf("query" to query, "sectionId" to library.id.toString(), "limit" to "40"))
        } catch (error: IOException) {
            if (error is PlexRequestException && error.status in setOf(401, 403)) throw error
            incomplete = error !is PlexRequestException || error.status != 404
            null
        }
        val scope = mapOf(library.id to library)
        val people = container?.searchRows("Hub").orEmpty().flatMap { hub ->
            (hub.searchRows("Directory") + hub.searchRows("Metadata")).mapNotNull { personFilter(hub, it, scope) }
        }.distinct().toMutableList()
        if (people.none { it.kind == "actor" }) {
            try {
                val actors = actorMatches(library, query)
                incomplete = incomplete || actors.incomplete
                people += actors.ids.map { PlexPersonFilter(library.id, "actor", it, if (library.type == "movie") "1" else "2") }
            } catch (error: IOException) {
                if (error is PlexRequestException && error.status in setOf(401, 403)) throw error
                incomplete = true
            }
        }
        if (people.size > 80) incomplete = true
        val path = if (people.isEmpty()) null else requireNotNull(resource("/library/sections/${library.id}/all"))
            .newBuilder().addQueryParameter("push", "1").addQueryParameter("title", query).apply {
                for ((kind, matches) in people.take(80).groupBy { it.kind }) {
                    addQueryParameter("or", "1")
                    addQueryParameter(kind, matches.map { it.id }.distinct().joinToString(","))
                }
                addQueryParameter("pop", "1")
            }.build().toString()
        currentCoroutineContext().ensureActive()
        if (!incomplete) personSearchCache.put(library, query, path)
        PlexLibraryPersonSearch(path, incomplete)
    } ?: PlexLibraryPersonSearch(null, incomplete = true)
}

/** PMS resolves localized names, aliases and spelling; a name string is never a local person ID. */
internal suspend fun PlexClient.searchResults(container: JSONObject, query: String,
    libraries: List<Library>?, onCatalogReady: suspend (List<MediaItem>) -> Unit): LibrarySearchResult {
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
    fun rankedItems(limit: Int = SEARCH_RESULT_LIMIT): List<MediaItem> {
        val ranked = hits.map { it to titleRank(it.row) }
        val evidenced = ranked.filter { it.second < 4 || it.first.personMatch }
        return (if (evidenced.isEmpty()) ranked else evidenced)
            .sortedBy { it.second }.map { it.first.item }.distinctBy { it.id }.take(limit)
    }
    currentCoroutineContext().ensureActive()
    onCatalogReady(rankedItems())
    currentCoroutineContext().ensureActive()
    val failed = people.drop(PERSON_HUB_LIMIT).mapTo(HashSet()) { it.libraryId }
    val jobs = mutableListOf<Pair<Int, suspend () -> PlexPersonExpansion>>()
    // Follow only the local library/person identity provided by PMS. Rebuild the read-only
    // filter instead of following arbitrary URL parameters, tokens or external profile links.
    // Search stays bounded like /hubs/search; this does not enumerate an entire library.
    for (person in people.take(PERSON_HUB_LIMIT)) {
        jobs += person.libraryId to suspend hubWork@ {
            val parameters = mutableMapOf(person.kind to person.id.toString(), "includeCollections" to "0",
                "X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to PERSON_WORK_LIMIT.toString())
            person.type?.let { parameters["type"] = it }
            val works = try {
                request("/library/sections/${person.libraryId}/all", parameters)
            } catch (error: IOException) {
                if (error is PlexRequestException && error.status in setOf(401, 403)) throw error
                null
            }
            if (works == null) return@hubWork PlexPersonExpansion(emptyList(), person.libraryId)
            val found = mutableListOf<PlexSearchHit>()
            for (row in works.searchRows("Metadata").take(PERSON_WORK_LIMIT)) {
                currentCoroutineContext().ensureActive()
                val hit = parseHit(row, person.libraryId, personMatch = true) ?: continue
                if (hit.item.libraryId != person.libraryId || hit.item.type !in setOf("movie", "show")) continue
                if (person.type == "1" && hit.item.type != "movie" || person.type == "2" && hit.item.type != "show") continue
                found += hit
            }
            PlexPersonExpansion(found, person.libraryId.takeIf {
                works.optInt("totalSize", found.size) > PERSON_WORK_LIMIT || works.searchRows("Metadata").size > PERSON_WORK_LIMIT })
        }
    }
    val excluded = scope.orEmpty().values.filter { it.id in excludedSearchSections }
    if (needle.isNotEmpty()) failed += excluded.drop(EXCLUDED_SECTION_LIMIT).map { it.id }
    if (needle.isNotEmpty()) for (library in excluded.take(EXCLUDED_SECTION_LIMIT)) {
        jobs += library.id to suspend facetWork@ {
            try {
                val actors = actorMatches(library, query)
                if (actors.ids.isEmpty()) return@facetWork PlexPersonExpansion(emptyList(), library.id.takeIf { actors.incomplete })
                val works = request("/library/sections/${library.id}/all", mapOf(
                    "type" to if (library.type == "movie") "1" else "2", "actor" to actors.ids.joinToString(","),
                    "includeCollections" to "0", "X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to SEARCH_RESULT_LIMIT.toString()),
                    maximumResponseBytes = 4L * 1024 * 1024)
                val rows = works.searchRows("Metadata")
                val found = rows.take(SEARCH_RESULT_LIMIT).mapNotNull { parseHit(it, library.id, personMatch = true) }
                    .filter { it.item.libraryId == library.id && it.item.type == if (library.type == "movie") "movie" else "show" }
                PlexPersonExpansion(found, library.id.takeIf { actors.incomplete || rows.size > SEARCH_RESULT_LIMIT ||
                    works.optInt("totalSize", rows.size) > SEARCH_RESULT_LIMIT })
            } catch (error: IOException) {
                if (error is PlexRequestException && error.status in setOf(401, 403)) throw error
                PlexPersonExpansion(emptyList(), library.id)
            }
        }
    }
    val completed = java.util.concurrent.ConcurrentHashMap<Int, PlexPersonExpansion>()
    // One deadline includes semaphore waiting, facet fetches and filtered works. Keep
    // completed work on expiry instead of losing it to awaitAll's cancellation.
    withTimeoutOrNull(8_000) {
        coroutineScope {
            val slots = Semaphore(2)
            jobs.mapIndexed { index, (_, work) -> async {
                slots.withPermit { currentCoroutineContext().ensureActive(); completed[index] = work() }
            } }.awaitAll()
        }
    }
    currentCoroutineContext().ensureActive()
    jobs.forEachIndexed { index, (libraryId, _) ->
        val result = completed[index]
        if (result == null) failed += libraryId else {
            hits += result.hits
            result.failedLibrary?.let(failed::add)
        }
    }
    val ranked = rankedItems(Int.MAX_VALUE)
    failed += ranked.drop(SEARCH_RESULT_LIMIT).map { it.libraryId }.filter { it > 0 }
    return LibrarySearchResult(ranked.take(SEARCH_RESULT_LIMIT), failed.size)
}
