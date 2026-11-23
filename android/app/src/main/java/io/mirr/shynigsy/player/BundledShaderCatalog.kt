package io.mirr.shynigsy.player

import android.content.Context
import org.json.JSONObject

internal data class BundledShader(val id: String, val label: String, val descriptionKo: String, val assetPaths: List<String>)
internal data class ShaderGroup(val name: String, val entries: List<BundledShader>)

internal class BundledShaderCatalog(val groups: List<ShaderGroup>) {
    private val byId = groups.flatMap { it.entries }.associateBy { it.id }

    fun find(id: String?): BundledShader? = id?.let(byId::get)

    companion object {
        @Volatile private var cached: BundledShaderCatalog? = null

        fun from(context: Context): BundledShaderCatalog = cached ?: synchronized(this) {
            cached ?: context.assets.open("shaders/plezy/catalog.json").bufferedReader().use { parse(it.readText()) }
                .also { cached = it }
        }

        internal fun parse(json: String): BundledShaderCatalog {
            val ids = mutableSetOf<String>()
            val groups = JSONObject(json).getJSONArray("groups")
            return BundledShaderCatalog((0 until groups.length()).map { index ->
                val group = groups.getJSONObject(index)
                val entries = group.getJSONArray("entries")
                ShaderGroup(group.getString("name"), (0 until entries.length()).map { row ->
                    val entry = entries.getJSONObject(row)
                    val id = "plezy/${entry.getString("id")}"
                    require(validLibraryShaderId(id) && ids.add(id))
                    val files = entry.getJSONArray("files")
                    require(files.length() > 0)
                    val paths = (0 until files.length()).map { file ->
                        val path = files.getString(file)
                        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
                        require(path.none { it == '\\' || it == ':' || it.isISOControl() })
                        require(path.endsWith(".glsl") || path.endsWith(".hook"))
                        "shaders/plezy/$path"
                    }
                    BundledShader(id, entry.getString("label"), entry.getString("descriptionKo"), paths)
                })
            })
        }
    }
}
