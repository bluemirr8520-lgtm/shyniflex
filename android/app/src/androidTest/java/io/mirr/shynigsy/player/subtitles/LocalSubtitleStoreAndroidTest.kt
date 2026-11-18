package io.mirr.shynigsy.player.subtitles

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalSubtitleStoreAndroidTest {
    @Test fun importReplacementSelectionAndFailureSurviveNewStoreInstances() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val original = File(folder, "subtitle-${UUID.randomUUID()}.srt")
        val invalid = File(folder, "subtitle-${UUID.randomUUID()}.zip")
        original.writeText("1\n00:00:01,000 --> 00:00:03,000\nSynthetic subtitle\n")
        invalid.writeBytes("PK\u0003\u0004".toByteArray())
        fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val store = LocalSubtitleStore(context)
        try {
            withContext(Dispatchers.IO) {
                val initial = store.importSubtitle(owner, uri(original))
                assertTrue(initial.selected)
                assertEquals("Synthetic subtitle", LocalSubtitleStore(context).load(owner, initial.id).single().text)
                assertFalse(store.select(owner, initial.id, false)!!.selected)
                assertFalse(LocalSubtitleStore(context).find(owner)!!.selected)
                assertTrue(runCatching { store.importSubtitle(owner, uri(invalid)) }.exceptionOrNull() is LocalSubtitleException)
                assertEquals(initial.id, store.find(owner)!!.id)
                assertFalse(store.find(owner)!!.selected)
                assertTrue(runCatching { store.importSubtitle(owner, Uri.fromFile(original)) }.isFailure)
                assertEquals(initial.id, store.find(owner)!!.id)
                assertTrue(store.select(owner, initial.id, true)!!.selected)
                val replacement = store.importSubtitle(owner, uri(original))
                assertNotEquals(initial.id, replacement.id)
                assertNull(store.select(owner, initial.id, true))
                assertEquals(replacement.id, LocalSubtitleStore(context).find(owner)!!.id)
                store.remove(owner)
                assertNull(LocalSubtitleStore(context).find(owner))
                assertTrue("Import must not modify or delete the selected source file", original.isFile)
            }
        } finally {
            withContext(Dispatchers.IO) { store.remove(owner) }
            original.delete(); invalid.delete()
        }
    }
}
