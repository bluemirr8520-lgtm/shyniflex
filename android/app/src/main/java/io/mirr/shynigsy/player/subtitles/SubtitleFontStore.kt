package io.mirr.shynigsy.player.subtitles

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

internal data class SubtitleFontEntry(val key: String, val label: String)

/** Imported fonts are copied into app storage; no continuing document grant is needed. */
internal class SubtitleFontStore(private val context: Context) {
    private val directory: File get() = File(context.filesDir, "subtitle-fonts")

    fun list(): List<SubtitleFontEntry> = synchronized(lock) {
        directory.listFiles().orEmpty().mapNotNull { file ->
            val digest = file.name.removeSuffix(".font")
            if (file.name != "$digest.font" || !digestPattern.matches(digest) ||
                !file.isFile || file.length() !in 12..MAX_FONT_BYTES
            ) return@mapNotNull null
            val key = PREFIX + digest
            SubtitleFontEntry(key, readLabel(digest))
        }.sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.key }))
    }

    fun importFont(uri: Uri): SubtitleFontEntry {
        var name: String? = null
        var declaredSize: Long? = null
        // Providers may omit SIZE, use a pipe, or reject a metadata projection.
        // The bounded stream copy below is authoritative in every case.
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { declaredSize = cursor.getLong(it) }
                }
            }
        } catch (_: Exception) {
            // Opening the selected document can still succeed without metadata.
        }
        if (declaredSize != null && declaredSize!! > MAX_FONT_BYTES) throw IOException("글꼴 파일은 20 MB 이하여야 합니다.")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("선택한 글꼴을 열 수 없습니다.")
        return importFont(input, name ?: uri.lastPathSegment.orEmpty())
    }

    /** Consumes and closes input, including when copying or validation fails. */
    fun importFont(input: InputStream, displayName: String): SubtitleFontEntry = input.use { source ->
        val folder = directory
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("글꼴 저장 공간을 준비하지 못했습니다.")
        val temporary = File.createTempFile("import-", ".tmp", folder)
        try {
            val sha = MessageDigest.getInstance("SHA-256")
            var count = 0L
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) {
                        val single = source.read()
                        if (single < 0) break
                        if (++count > MAX_FONT_BYTES) throw IOException("글꼴 파일은 20 MB 이하여야 합니다.")
                        output.write(single)
                        sha.update(single.toByte())
                    } else {
                        count += read
                        if (count > MAX_FONT_BYTES) throw IOException("글꼴 파일은 20 MB 이하여야 합니다.")
                        output.write(buffer, 0, read)
                        sha.update(buffer, 0, read)
                    }
                }
                output.fd.sync()
            }
            validateHeader(temporary)
            val validated = try { Typeface.Builder(temporary).build() } catch (_: RuntimeException) { null }
                ?: throw IOException("올바른 TTF 또는 OTF 글꼴이 아닙니다.")
            val digest = sha.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
            val target = File(folder, "$digest.font")
            synchronized(lock) {
                if (!target.exists() && !temporary.renameTo(target)) throw IOException("글꼴 파일을 저장하지 못했습니다.")
                // Identical font bytes keep a stable key and their first chosen name.
                val name = File(folder, "$digest.name")
                if (!name.exists()) writeLabel(name, sanitizeName(displayName))
                cache(target, validated)
                SubtitleFontEntry(PREFIX + digest, readLabel(digest))
            }
        } finally {
            temporary.delete()
        }
    }

    fun resolve(key: String): Typeface? = synchronized(lock) {
        val digest = digestFromKey(key) ?: return@synchronized null
        val file = File(directory, "$digest.font")
        if (!file.isFile || file.length() !in 12..MAX_FONT_BYTES) return@synchronized null
        val cached = typefaces[file.absolutePath]
        if (cached != null && cached.modified == file.lastModified() && cached.bytes == file.length()) return@synchronized cached.typeface
        val typeface = try {
            validateHeader(file)
            Typeface.Builder(file).build()
        } catch (_: Exception) { null } ?: return@synchronized null
        cache(file, typeface)
        typeface
    }

    /** Removes only a validated store key, never an arbitrary path. */
    fun remove(key: String): Boolean = synchronized(lock) {
        val digest = digestFromKey(key) ?: return@synchronized false
        val file = File(directory, "$digest.font")
        val removed = file.isFile && file.delete()
        if (removed) {
            AtomicFile(File(directory, "$digest.name")).delete()
            typefaces.remove(file.absolutePath)
        }
        removed
    }

    private fun readLabel(digest: String): String {
        val file = File(directory, "$digest.name")
        val stored = if (file.isFile && file.length() in 1..1024) {
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        } else null
        return stored?.let(::sanitizeName) ?: "사용자 글꼴 ${digest.take(8)}"
    }

    private fun writeLabel(file: File, value: String) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(value.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun validateHeader(file: File) {
        if (file.length() < 12) throw IOException("글꼴 파일이 비어 있거나 손상되었습니다.")
        val header = ByteArray(4)
        file.inputStream().use { stream ->
            var offset = 0
            while (offset < header.size) {
                val read = stream.read(header, offset, header.size - offset)
                if (read <= 0) throw IOException("글꼴 파일이 손상되었습니다.")
                offset += read
            }
        }
        val signature = String(header, Charsets.ISO_8859_1)
        if (!(header.contentEquals(byteArrayOf(0, 1, 0, 0)) || signature in setOf("OTTO", "ttcf", "true")))
            throw IOException("TTF 또는 OTF 글꼴 파일을 선택하세요.")
    }

    private fun cache(file: File, typeface: Typeface) {
        typefaces[file.absolutePath] = CachedTypeface(file.lastModified(), file.length(), typeface)
        while (typefaces.size > 4) typefaces.entries.iterator().apply { next(); remove() }
    }

    private fun digestFromKey(key: String): String? =
        key.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf(digestPattern::matches)

    private fun sanitizeName(value: String): String {
        val filename = value.substringAfterLast('/').substringAfterLast('\\')
        val normalized = Normalizer.normalize(filename, Normalizer.Form.NFC)
            .replace(Regex("[\\p{Cntrl}\\p{Cf}]"), "")
            .replace(Regex("(?i)\\.(?:ttf|otf|ttc)$"), "")
            .replace(Regex("\\s+"), " ").trim().trim('.')
        val points = normalized.codePoints().limit(80).toArray()
        return String(points, 0, points.size).ifBlank { "사용자 글꼴" }
    }

    companion object {
        const val MAX_FONT_BYTES = 20L * 1024 * 1024
        private const val PREFIX = "custom_"
        private val digestPattern = Regex("[0-9a-f]{64}")
        private val lock = Any()
        private data class CachedTypeface(val modified: Long, val bytes: Long, val typeface: Typeface)
        private val typefaces = LinkedHashMap<String, CachedTypeface>(4, .75f, true)
    }
}
