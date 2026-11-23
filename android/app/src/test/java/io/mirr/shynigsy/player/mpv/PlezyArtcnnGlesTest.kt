package io.mirr.shynigsy.player.mpv

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PlezyArtcnnGlesTest {
    @Test fun allSixFragmentPathsKeepEveryPassWeightBiasResidualAndDepthShuffle() {
        val path = "shaders/plezy/artcnn"
        val files = File("src/main/assets/$path").listFiles { file -> file.extension == "glsl" }.orEmpty()
        assertEquals(6, files.size)
        for (file in files) {
            val source = file.readText()
            val result = plezyArtcnnGles("$path/${file.name}", source)
            for (token in listOf("//!COMPUTE", "#extension", "imageStore", "shared ", "gl_LocalInvocationID"))
                assertFalse("Unexpected compute-only token: $token", token in result)
            fun stages(text: String) = text.lineSequence().filter { it.startsWith("//!") && !it.startsWith("//!COMPUTE") }.toList()
            fun arithmetic(text: String) = text.lineSequence().map(String::trim).filter {
                it.startsWith("V4 result") || it.startsWith("result") && " += " in it
            }.sorted().toList()
            val layers = source.substringBeforeLast("//!DESC ")
            val shuffle = source.substringAfterLast("//!DESC ").replace("//!COMPONENTS 4", "//!COMPONENTS 1")
            if ("C4F32" in file.name) {
                val generated = result.split("//!DESC ").drop(1)
                assertTrue(generated.all { pass -> pass.lineSequence().count { it.startsWith("//!BIND ") } <= 8 })
                assertTrue(stages(source).filter { it.startsWith("//!DESC ") }.all { it in stages(result) })
                assertTrue("Preserve residual inputs", result.lineSequence().any {
                    it.contains("art32_conv2d_5_0_mul") && it.contains("art32_conv2d_0_mul")
                })
            } else {
                assertEquals(stages(layers + "//!DESC " + shuffle), stages(result))
                assertTrue("Skip connection must survive", "+ conv2d_mul * texelFetch" in result)
            }
            assertEquals(arithmetic(source), arithmetic(result))
            assertTrue("Depth-to-space indexing must survive", "[i0.y * 2 + i0.x]" in result)
            assertEquals(result, plezyArtcnnGles("$path/${file.name}", result))
            assertEquals(source, plezyArtcnnGles("shaders/other/${file.name}", source))
        }
    }
}
