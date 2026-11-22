package io.mirr.shynigsy.player

import android.content.Context
import io.mirr.shynigsy.data.SubtitleTrack
import io.mirr.shynigsy.player.subtitles.LocalSubtitleStore
import io.mirr.shynigsy.player.subtitles.LocalSubtitleWrites
import io.mirr.shynigsy.player.subtitles.SubtitleLoader
import io.mirr.shynigsy.player.subtitles.SubtitleTimeline
import io.mirr.shynigsy.player.subtitles.sanitizeSubtitleLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Independent text timeline; calls and callbacks belong on the player's main thread. */
internal class LanguageStudySecondary(
    context: Context,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
    private val onError: (String) -> Unit,
    private val allowed: () -> Boolean,
) {
    private val store = LocalSubtitleStore(context)
    private var request: Job? = null
    private var revision = 0L

    var ownerKey: String? = null
        private set
    var timeline: SubtitleTimeline? = null
        private set
    var label: String = "끔"
        private set
    var loading: Boolean = false
        private set

    fun reset(primaryOwner: String?) {
        val next = languageStudySecondaryOwner(primaryOwner)
        if (next == ownerKey) return
        ownerKey = next
        clear()
    }

    fun loadServer(track: SubtitleTrack, loader: SubtitleLoader) {
        load("추가 자막을 불러오지 못했습니다. 텍스트 자막을 선택하거나 다른 자막 파일을 사용해 주세요.") {
            val cues = loader.load(track)
            val result = withContext(Dispatchers.Default) { SubtitleTimeline(cues) }
            Loaded(result, sanitizeSubtitleLabel(track.label))
        }
    }

    fun loadLocal(id: String? = null) {
        load("저장된 추가 자막을 불러오지 못했습니다. 자막 파일을 다시 선택해 주세요.") { owner ->
            withContext(Dispatchers.IO) {
                LocalSubtitleWrites.awaitPending()
                val record = store.find(owner)
                    ?.takeIf { id == null || it.id == id }
                    ?: throw MissingLocalSubtitle()
                Loaded(SubtitleTimeline(store.load(owner, record.id)), record.label)
            }
        }
    }

    fun acceptImported(owner: String, id: String) {
        if (owner == ownerKey && allowed()) loadLocal(id)
    }

    fun clear() {
        revision++
        request?.cancel()
        request = null
        timeline = null
        label = "끔"
        loading = false
        onChanged()
    }

    fun release() {
        ownerKey = null
        clear()
    }

    private fun load(failureMessage: String, read: suspend (String) -> Loaded) {
        if (!scope.isActive || !allowed()) return
        val owner = ownerKey ?: run {
            onError("현재 영상의 추가 자막 정보를 확인할 수 없습니다.")
            return
        }
        val ticket = ++revision
        request?.cancel()
        timeline = null
        label = "불러오는 중…"
        loading = true
        onChanged()
        request = scope.launch {
            try {
                if (!current(owner, ticket)) return@launch
                val loaded = read(owner)
                if (current(owner, ticket)) {
                    timeline = loaded.timeline
                    label = loaded.label
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (current(owner, ticket)) {
                    timeline = null
                    label = "끔"
                    onError(failureMessage)
                }
            } finally {
                // Clear bookkeeping even when the player was backgrounded or
                // locked. Stale results and callbacks must not expose subtitles.
                if (owner == ownerKey && ticket == revision) {
                    request = null
                    loading = false
                    if (timeline == null) label = "끔"
                    if (allowed()) onChanged()
                    else {
                        timeline = null
                        label = "끔"
                    }
                }
            }
        }
    }

    private fun current(owner: String, ticket: Long): Boolean =
        owner == ownerKey && ticket == revision && allowed()

    private data class Loaded(val timeline: SubtitleTimeline, val label: String)
    private class MissingLocalSubtitle : Exception()
}

/** The primary owner already isolates account/server/media; this namespace isolates the second file. */
internal fun languageStudySecondaryOwner(primaryOwner: String?): String? {
    if (primaryOwner.isNullOrBlank()) return null
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("shyniflex-language-study-secondary-v1\u0000".toByteArray(Charsets.UTF_8))
    digest.update(primaryOwner.toByteArray(Charsets.UTF_8))
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
