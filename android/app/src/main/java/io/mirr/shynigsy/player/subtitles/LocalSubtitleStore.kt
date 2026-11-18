package io.mirr.shynigsy.player.subtitles

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

internal data class LocalSubtitleRecord(val id: String, val label: String, val selected: Boolean)

internal class LocalSubtitleException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** One validated subtitle per opaque account/media owner. All public calls belong on IO. */
internal class LocalSubtitleStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.applicationContext.filesDir, "local_subtitles_v1")

    suspend fun find(ownerKey: String): LocalSubtitleRecord? {
        validateOwner(ownerKey)
        currentCoroutineContext().ensureActive()
        return synchronized(lock) { read(ownerKey)?.record }
    }

    suspend fun importSubtitle(ownerKey: String, uri: Uri): LocalSubtitleRecord {
        validateOwner(ownerKey)
        if (uri.scheme != "content") throw LocalSubtitleException("기기의 문서 선택 창에서 자막 파일을 선택해 주세요.")
        val label = displayName(uri)
        val bytes = try {
            val input = resolver.openInputStream(uri) ?: throw LocalSubtitleException("자막 파일을 열 수 없습니다.")
            input.use { readLocalSubtitleBytes(it) }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
          catch (error: LocalSubtitleException) { throw error }
          catch (error: SecurityException) { throw LocalSubtitleException("선택한 자막 파일을 읽을 권한이 없습니다.", error) }
          catch (error: IOException) { throw LocalSubtitleException("자막 파일을 끝까지 읽지 못했습니다.", error) }
        return importBytes(ownerKey, label, bytes)
    }

    suspend fun importBytes(ownerKey: String, label: String, bytes: ByteArray): LocalSubtitleRecord {
        validateOwner(ownerKey)
        if (bytes.size.toLong() > MAX_FILE_BYTES) throw LocalSubtitleException("자막 파일은 8MB 이하여야 합니다.")
        val ownedBytes = bytes.copyOf()
        parse(ownedBytes)
        val record = LocalSubtitleRecord(UUID.randomUUID().toString(), sanitizeSubtitleLabel(label), true)
        val job = currentCoroutineContext()
        job.ensureActive()
        synchronized(lock) {
            job.ensureActive()
            write(ownerKey, Stored(record, ownedBytes)) { job.ensureActive() }
            trim(ownerKey)
        }
        return record
    }

    suspend fun load(ownerKey: String, id: String): List<ManualSubtitleCue> {
        validateOwner(ownerKey)
        currentCoroutineContext().ensureActive()
        val stored = synchronized(lock) { read(ownerKey) }
            ?.takeIf { it.record.id == id } ?: throw LocalSubtitleException("저장된 자막 파일을 찾을 수 없습니다.")
        return parse(stored.bytes)
    }

    suspend fun select(ownerKey: String, id: String, selected: Boolean): LocalSubtitleRecord? {
        validateOwner(ownerKey)
        val job = currentCoroutineContext()
        job.ensureActive()
        return synchronized(lock) {
            val stored = read(ownerKey)?.takeIf { it.record.id == id } ?: return@synchronized null
            val changed = stored.copy(record = stored.record.copy(selected = selected))
            write(ownerKey, changed) { job.ensureActive() }
            changed.record
        }
    }

    suspend fun selectCurrent(ownerKey: String, selected: Boolean): LocalSubtitleRecord? {
        validateOwner(ownerKey)
        val job = currentCoroutineContext()
        job.ensureActive()
        return synchronized(lock) {
            val stored = read(ownerKey) ?: return@synchronized null
            val changed = stored.copy(record = stored.record.copy(selected = selected))
            write(ownerKey, changed) { job.ensureActive() }
            changed.record
        }
    }

    suspend fun remove(ownerKey: String) {
        validateOwner(ownerKey)
        currentCoroutineContext().ensureActive()
        synchronized(lock) { atomic(ownerKey).delete() }
    }

    suspend fun removeIf(ownerKey: String, id: String): Boolean {
        validateOwner(ownerKey)
        currentCoroutineContext().ensureActive()
        return synchronized(lock) {
            if (read(ownerKey)?.record?.id != id) false
            else { atomic(ownerKey).delete(); true }
        }
    }

    private fun displayName(uri: Uri): String {
        val value = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) { null }
        return sanitizeSubtitleLabel(value)
    }

    private fun atomic(ownerKey: String) = AtomicFile(File(directory, "$ownerKey.subtitle"))

    private fun read(ownerKey: String): Stored? {
        val file = atomic(ownerKey)
        return try {
            DataInputStream(file.openRead()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                val id = input.readUTF().takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) } ?: return null
                val label = sanitizeSubtitleLabel(input.readUTF())
                val selected = input.readBoolean()
                val length = input.readInt()
                if (length !in 1..MAX_FILE_BYTES.toInt()) return null
                val bytes = ByteArray(length)
                input.readFully(bytes)
                if (input.read() != -1) return null
                Stored(LocalSubtitleRecord(id, label, selected), bytes)
            }
        } catch (_: IOException) { null }
    }

    private fun write(ownerKey: String, stored: Stored, beforeCommit: () -> Unit) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory)
            throw LocalSubtitleException("자막 저장 공간을 만들지 못했습니다.")
        val atomic = atomic(ownerKey)
        val stream = try { atomic.startWrite() } catch (error: IOException) {
            throw LocalSubtitleException("자막 저장 공간을 열지 못했습니다.", error)
        }
        try {
            val output = DataOutputStream(stream)
            output.writeInt(MAGIC); output.writeInt(VERSION)
            output.writeUTF(stored.record.id); output.writeUTF(stored.record.label)
            output.writeBoolean(stored.record.selected); output.writeInt(stored.bytes.size)
            output.write(stored.bytes); output.flush()
            beforeCommit()
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw LocalSubtitleException("자막을 저장하지 못했습니다.", error)
        }
    }

    /** Evict oldest other records only after the replacement was committed. */
    private fun trim(currentOwner: String) {
        val files = directory.listFiles { file -> file.isFile && file.name.matches(RECORD_NAME) }?.toList() ?: return
        var size = files.sumOf { it.length() }
        for (file in files.filter { it.name != "$currentOwner.subtitle" }.sortedBy { it.lastModified() }) {
            if (size <= MAX_TOTAL_BYTES) break
            val length = file.length()
            AtomicFile(file).delete()
            if (!file.exists()) size -= length
        }
    }

    private data class Stored(val record: LocalSubtitleRecord, val bytes: ByteArray)

    private companion object {
        val lock = Any()
        val RECORD_NAME = Regex("[0-9a-f]{64}\\.subtitle")
        const val MAGIC = 0x53465355
        const val VERSION = 1
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    }
}

internal fun sanitizeSubtitleLabel(value: String?): String {
    val name = value.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        .filterNot { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }
        .trim().take(80).trim()
    return name.ifEmpty { "외부 자막" }
}

internal suspend fun readLocalSubtitleBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var emptyReads = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) {
            if (++emptyReads > 16) throw IOException("Empty subtitle reads")
            continue
        }
        emptyReads = 0
        if (output.size().toLong() + count > 8L * 1024 * 1024)
            throw LocalSubtitleException("자막 파일은 8MB 이하여야 합니다.")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun validateOwner(ownerKey: String) {
    require(ownerKey.matches(Regex("[0-9a-f]{64}"))) { "올바르지 않은 자막 저장 정보입니다." }
}

internal fun parseLocalSubtitle(bytes: ByteArray): List<ManualSubtitleCue> {
    if (bytes.isEmpty()) throw LocalSubtitleException("자막 파일이 비어 있습니다.")
    val isZip = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    val isPng = bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
    val isJpeg = bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
    val isGif = bytes.size >= 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()
    if (isZip || isPng || isJpeg || isGif) throw LocalSubtitleException("압축 파일과 이미지 자막은 불러올 수 없습니다.")
    val text = decodeSubtitleBytes(bytes)
    if (text.any { Character.isISOControl(it) && it != '\n' && it != '\r' && it != '\t' })
        throw LocalSubtitleException("지원하지 않는 자막 파일입니다.")
    return parseManualSubtitleCues(text).takeIf { it.isNotEmpty() }
        ?: throw LocalSubtitleException("표시할 수 있는 자막이 없습니다. SRT, ASS, VTT, SMI 또는 TTML 파일을 선택해 주세요.")
}

private fun parse(bytes: ByteArray) = parseLocalSubtitle(bytes)
