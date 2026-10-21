package io.mirr.shynigsy

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.player.subtitles.PlexSubtitleFonts
import io.mirr.shynigsy.player.subtitles.SubtitleFontStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CustomSubtitleFontTest {
    @Test
    fun externalTtfCanBeImportedListedAndResolvedAfterReopeningTheStore() = withStore { context, store ->
        val entry = store.importFont(importSample(), "../외부 글꼴.ttf")
        assertTrue(entry.key.matches(Regex("custom_[0-9a-f]{64}")))
        assertEquals("외부 글꼴", entry.label)
        assertEquals(listOf(entry), store.list())
        assertNotNull(store.resolve(entry.key))
        assertNotSame(Typeface.DEFAULT, PlexSubtitleFonts.typeface(context, entry.key))
        assertTrue(PlexSubtitleFonts.options(context).any { it.key == entry.key && it.label == entry.label })
        assertNotNull(SubtitleFontStore(context).resolve(entry.key))
        assertEquals(entry, SubtitleFontStore(context).list().single())
        assertEquals(listOf("system"), PlexSubtitleFonts.keys)
        assertEquals(listOf("시스템 기본", entry.label), PlexSubtitleFonts.options(context).map { it.label })
        assertEquals("An imported selection survives upgrading the built-in font list", entry.key,
            PlexSubtitleFonts.normalizedKey(context, entry.key))
        listOf("cinema", "asia_cinema_b", "asia_cinema_m", "asia_cinema_l", "serif", "gothic", "unknown").forEach { removed ->
            assertEquals("A removed built-in selection falls back to system", "system", PlexSubtitleFonts.normalizedKey(context, removed))
            assertEquals(Typeface.DEFAULT, PlexSubtitleFonts.typeface(context, removed))
        }
        assertEquals("system", PlexSubtitleFonts.normalizedKey(context, null))
        assertEquals("system", PlexSubtitleFonts.normalizedKey(context, "custom_" + "0".repeat(64)))
        assertEquals("Migration never removes an imported font", listOf(entry), store.list())

        val duplicate = store.importFont(importSample(), "renamed.ttf")
        assertEquals("Identical bytes keep the selected font key stable", entry.key, duplicate.key)
        assertEquals(1, store.list().size)
        assertNull(store.resolve("custom_../../outside.ttf"))
        assertFalse(store.remove("../../outside.ttf"))
        assertTrue(store.remove(entry.key))
        assertNull(store.resolve(entry.key))
        assertEquals(Typeface.DEFAULT, PlexSubtitleFonts.typeface(context, entry.key))
        assertEquals("system", PlexSubtitleFonts.normalizedKey(context, entry.key))
    }

    @Test
    fun documentUriImportCopiesTheFileInsteadOfDependingOnItsOriginalLocation() = withStore { context, store ->
        val source = File(context.filesDir, "선택한 글꼴.ttf")
        importSample().use { input -> source.outputStream().use(input::copyTo) }
        val entry = store.importFont(Uri.fromFile(source))
        assertTrue(source.delete())
        assertNotNull("App-private font survives deletion of the original document", SubtitleFontStore(context).resolve(entry.key))
    }

    @Test
    fun malformedFontAndSpoofedHeaderAreRejectedWithoutLeavingImportFiles() = withStore { _, store ->
        listOf(
            byteArrayOf(),
            "Not a font file".toByteArray(),
            "wOFFnot-a-supported-font".toByteArray(),
            byteArrayOf(0, 1, 0, 0) + ByteArray(60),
            "OTTO".toByteArray() + ByteArray(60),
        ).forEach { bytes ->
            var closed = false
            val input = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
            assertImportFails { store.importFont(input, "invalid.ttf") }
            assertTrue("Rejected streams must be closed", closed)
            assertTrue(store.list().isEmpty())
        }
    }

    @Test
    fun oversizedStreamsAreRejectedDuringTheCopyAndClosed() = withStore { context, store ->
        var closed = false
        var bytesRead = 0L
        val input = object : InputStream() {
            override fun read(): Int { bytesRead++; return 0 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(0, offset, offset + length)
                bytesRead += length
                return length
            }
            override fun close() { closed = true }
        }
        assertImportFails { store.importFont(input, "too-large.ttf") }
        assertTrue(closed)
        assertTrue(bytesRead > SubtitleFontStore.MAX_FONT_BYTES)
        assertTrue(bytesRead <= SubtitleFontStore.MAX_FONT_BYTES + 65_536)
        assertTrue(store.list().isEmpty())
        assertTrue("Failed imports leave no temporary font files", File(context.filesDir, "subtitle-fonts").listFiles().orEmpty().isEmpty())
    }

    private fun assertImportFails(action: () -> Unit) {
        try { action(); fail("Expected invalid or oversized font to be rejected") } catch (_: IOException) { }
    }

    private fun importSample(): InputStream =
        InstrumentationRegistry.getInstrumentation().context.assets.open("fonts/import-sample.ttf")

    private fun withStore(action: (Context, SubtitleFontStore) -> Unit) {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(original.cacheDir, "custom-font-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(original) { override fun getFilesDir(): File = directory }
        try { action(context, SubtitleFontStore(context)) } finally {
            // This directory was created above for this test; never touch the app's real font store.
            directory.deleteRecursively()
        }
    }
}
