package io.mirr.shynigsy.player.mpv

import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MpvShaderChainTest {
    @get:Rule val folder = TemporaryFolder()
    private val first = "shaders/plezy/anime4k/first.glsl"
    private val second = "shaders/plezy/anime4k/second.glsl"
    private val assets = mutableMapOf(first to "first", second to "second")
    private val catalog = mutableMapOf("chain" to listOf(second, first, second), "single" to listOf(first))
    private val posted = LinkedBlockingQueue<() -> Unit>()
    private val applied = mutableListOf<List<File>>()
    private val failed = mutableListOf<String>()
    private fun newChain() = MpvShaderChain(folder.root, { catalog[it].orEmpty() },
        { assets[it]?.byteInputStream() ?: throw FileNotFoundException() }, { MpvShaderGpu() },
        { posted.add(it) }, { applied.add(it) }, { failed.add(it) })
    private fun next() = requireNotNull(posted.poll(5, TimeUnit.SECONDS))

    @Test fun redBalanceChangesOnlyKnownSdrAndResumesWhenTheStreamChangesBack() {
        val props = mutableMapOf<String, String>()
        fun tone() = sdrRedTone(-8, props::get)
        assertEquals(0, tone())
        props["video-params/gamma"] = "bt.1886"
        assertEquals(-8, tone())
        for (transfer in listOf("pq", "hlg", "v-log", "unknown")) {
            props["video-params/gamma"] = transfer
            assertEquals(0, tone())
        }
        props["video-params/gamma"] = "srgb"
        for (key in listOf("primaries", "colormatrix")) {
            props["video-params/$key"] = "bt.2020-ncl"
            assertEquals(0, tone())
            props.remove("video-params/$key")
        }
        assertEquals(-8, tone())
        assertEquals(30, sdrRedTone(200, props::get))
        assertEquals(0, sdrRedTone(0) { error("Neutral should not inspect metadata") })
    }

    @Test fun sdrBalanceAndPresetsCanEachBeAdjustedOrRemovedWithoutLosingTheOther() {
        val chain = newChain()
        try {
            chain.setRedTone(-8)
            next().invoke()
            val cool = applied.last().single()
            assertTrue(cool.readText().contains("c.r = clamp(c.r + (8.0 / 100.0) * (dot(c.gb, vec2(0.75, 0.25)) - c.r), 0.0, 1.0)"))
            chain.select("single")
            next().invoke()
            val preset = applied.last().first()
            assertEquals(listOf(preset, cool), applied.last())
            chain.setRedTone(10)
            val outdated = next()
            chain.setRedTone(20)
            val current = next()
            outdated()
            assertEquals(listOf(preset, cool), applied.last())
            current()
            val warm = applied.last().last()
            assertTrue(warm.readText().contains("(-20.0 / 100.0)"))
            chain.select(null)
            assertEquals(listOf(warm), applied.last())
            chain.setRedTone(0)
            assertTrue(applied.last().isEmpty())
        } finally { chain.close() }
    }

    @Test fun anonymousBalanceErrorRetriesThePresetBeforeReportingLibraryFailure() {
        val chain = newChain()
        try {
            chain.select("single")
            next().invoke()
            val preset = applied.last().single()
            chain.setRedTone(-8)
            next().invoke()
            val balance = applied.last().last()
            chain.rendererFailed()
            next().invoke()
            assertEquals(listOf(preset), applied.last())
            assertTrue(failed.isEmpty())
            chain.rendererFailed()
            next().invoke()
            assertEquals(listOf("single"), failed)
            assertEquals(listOf(balance), applied.last())
            chain.rendererFailed()
            next().invoke()
            assertTrue(applied.last().isEmpty())
            assertEquals(listOf("single"), failed)
        } finally { chain.close() }
    }

    @Test fun nvScalerTemporarilySkipsDecodedHdrAndResumesOnSdrWithoutChangingTheRequest() {
        val properties = mutableMapOf<String, String>()
        val id = PLEZY_NVSCALER_ID
        assertEquals(id, effectivePlezyShader(id, properties::get))
        for ((key, value) in listOf("colormatrix" to "bt.2020-ncl", "primaries" to "bt.2020",
                "gamma" to "pq", "gamma" to "hlg")) {
            properties["video-params/$key"] = value
            assertNull(effectivePlezyShader(id, properties::get))
            properties.clear()
            properties["video-params/primaries"] = "bt.709"
            assertEquals(id, effectivePlezyShader(id, properties::get))
        }
        assertNull(effectivePlezyShader(null) { error("Off should not inspect video metadata") })
        assertEquals("plezy/artcnn", effectivePlezyShader("plezy/artcnn") { error("Other presets keep their own rules") })
    }

    @Test fun originalChainOrderAndRepeatedPathsSurviveLiveEnableAndDisable() {
        val chain = newChain()
        try {
            chain.select("chain")
            assertTrue(applied.isEmpty())
            next().invoke()
            val custom = applied.single()
            assertEquals(listOf("second", "first", "second"), custom.map { it.readText() })
            assertEquals(custom[0], custom[2])
            chain.select("chain")
            assertEquals(custom, applied.last())
            chain.select(null)
            assertTrue(applied.last().isEmpty())
            assertTrue(failed.isEmpty())
        } finally { chain.close() }
    }

    @Test fun rapidlyChangedAndReleasedSelectionsIgnoreAlreadyPostedResults() {
        val chain = newChain()
        try {
            chain.select("single")
            val old = next()
            chain.select("chain")
            val replacement = next()
            old()
            assertTrue(applied.isEmpty())
            chain.select(null)
            replacement()
            assertTrue(applied.isEmpty())
            chain.select("chain")
            val afterRelease = next()
            chain.close()
            afterRelease()
            assertTrue(applied.isEmpty())
            assertTrue(failed.isEmpty())
        } finally { chain.close() }
    }

    @Test fun missingObsoleteOrUnsafePathsNeverApplyAPartialChain() {
        catalog["missing"] = listOf(first, "shaders/plezy/missing.glsl")
        catalog["unsafe"] = listOf("shaders/plezy/../escape.glsl")
        assets["shaders/plezy/../escape.glsl"] = "unsafe"
        catalog["obsolete"] = listOf("shaders/previous/old.glsl")
        assets["shaders/previous/old.glsl"] = "old content"
        val chain = newChain()
        try {
            for (id in listOf("missing", "unsafe", "obsolete", "unknown")) {
                chain.select(id)
                next().invoke()
            }
            assertEquals(listOf("missing", "unsafe", "obsolete", "unknown"), failed)
            assertTrue(applied.isEmpty())
        } finally { chain.close() }
    }

    @Test fun replacementApkContentGetsANewImmutablePathAndRepairsACorruptCopy() {
        fun install(): File {
            val chain = newChain()
            return try {
                chain.select("single")
                next().invoke()
                applied.last().single()
            } finally { chain.close() }
        }
        val old = install()
        assets[first] = "new revision"
        val updated = install()
        assertNotEquals(old, updated)
        assertEquals("first", old.readText())
        updated.writeText("bad revision")
        assertEquals(updated, install())
        assertEquals("new revision", updated.readText())
    }

    @Test fun upgradeRemovesOnlyThePreviousPlayerGeneratedFiles() {
        val oldDirectory = folder.newFolder("mpv-shader-library")
        val generated = oldDirectory.resolve("a".repeat(64) + ".glsl").apply { writeText("old") }
        val other = oldDirectory.resolve("unrelated.txt").apply { writeText("keep") }
        val oldDenoise = folder.newFile("playback-denoise-3.glsl")
        val chain = newChain()
        try {
            chain.discardPreviousShaderCache(folder.root)
            chain.select("single")
            next().invoke()
            assertFalse(generated.exists())
            assertFalse(oldDenoise.exists())
            assertTrue(other.isFile)
            assertTrue(applied.single().single().isFile)
        } finally { chain.close() }
    }

    @Test fun compilerFailureDisablesOnlyTheCurrentLibraryAndObsoleteDiagnosticsAreIgnored() {
        val chain = newChain()
        try {
            chain.select("chain")
            next().invoke()
            chain.rendererFailed()
            val stale = next()
            chain.select("single")
            next().invoke()
            stale()
            assertTrue(failed.isEmpty())
            chain.rendererFailed()
            next().invoke()
            assertTrue(applied.last().isEmpty())
            assertEquals(listOf("single"), failed)
        } finally { chain.close() }
    }

    @Test fun shaderRequirementsUseTheRealWorkgroupLimitsTextureSizeAndRequiredExtensions() {
        val gpu = MpvShaderGpu(3, 128, 128, 128, 4096, 256)
        gpu.checkRequirements("//!COMPUTE 16 8")
        gpu.checkRequirements("#extension GL_EXT_optional : enable")
        gpu.checkRequirements("//!TEXTURE LUT\n//!SIZE 4 64\n//!FORMAT rgba32f\n//!FILTER NEAREST")
        for (source in listOf("//!COMPUTE 32 24 256 1", "//!COMPUTE 16 16", "//!SIZE 2 2 512",
                "#extension GL_EXT_required : require", "//!TEXTURE LUT\n//!SIZE 4 64\n//!FORMAT rgba32f\n//!FILTER LINEAR")) {
            assertThrows(IllegalArgumentException::class.java) { gpu.checkRequirements(source) }
        }
        assertThrows(IllegalArgumentException::class.java) { MpvShaderGpu().checkRequirements("textureGatherOffset(tex, pos, off, 0)") }
    }
}
