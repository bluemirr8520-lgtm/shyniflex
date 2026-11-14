package io.mirr.shynigsy.data

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

private const val MAX_ORDER_IDS = 2_000

/** Only an authenticated account name and its exact origin share a saved presentation order. */
internal fun libraryOrderScope(server: String, userName: String): String? {
    val account = userName.trim().takeIf { it.isNotEmpty() && it != "사용자" } ?: return null
    val origin = server.trim().toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/" &&
            it.query == null && it.fragment == null
    } ?: return null
    return MessageDigest.getInstance("SHA-256")
        .digest((origin.toString() + "\u0000" + account).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun decodeLibraryOrder(value: String?): List<Int> {
    if (value.isNullOrBlank() || value.length > 24_000) return emptyList()
    return value.split(',').asSequence().mapNotNull { it.toIntOrNull()?.takeIf { id -> id > 0 } }
        .distinct().take(MAX_ORDER_IDS).toList()
}

internal fun applyLibraryOrder(libraries: List<Library>, saved: List<Int>): List<Library> {
    val positions = saved.filter { it > 0 }.distinct().withIndex().associate { it.value to it.index }
    // Stable sorting keeps new libraries in their server order after previously arranged ones.
    return libraries.sortedBy { positions[it.id] ?: Int.MAX_VALUE }
}

/** Replace visible slots only: locking or losing access never removes hidden saved positions. */
internal fun moveVisibleLibraryOrder(saved: List<Int>, visibleIds: List<Int>, id: Int, direction: Int): List<Int> {
    val visible = visibleIds.filter { it > 0 }.distinct()
    val from = visible.indexOf(id)
    val to = from + direction
    if (direction !in listOf(-1, 1) || from < 0 || to !in visible.indices) return saved
    val reordered = visible.toMutableList().apply {
        val adjacent = this[to]
        this[to] = this[from]
        this[from] = adjacent
    }
    val visibleSet = visible.toSet()
    val base = (saved.filter { it > 0 } + visible).distinct()
    var next = 0
    return base.map { if (it in visibleSet) reordered[next++] else it }.take(MAX_ORDER_IDS)
}

/** Device-local preferences contain only opaque account keys and numeric View IDs, never titles. */
internal class LibraryOrderStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("library_order", Context.MODE_PRIVATE)

    fun read(scope: String?): List<Int> = scope?.let {
        decodeLibraryOrder(preferences.getString(it, null))
    }.orEmpty()

    fun save(scope: String, order: List<Int>) {
        require(scope.matches(Regex("[a-f0-9]{64}")))
        preferences.edit().putString(scope, order.filter { it > 0 }.distinct().take(MAX_ORDER_IDS).joinToString(",")).apply()
    }

    fun reset(scope: String) {
        require(scope.matches(Regex("[a-f0-9]{64}")))
        preferences.edit().remove(scope).apply()
    }
}
