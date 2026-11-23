package io.mirr.shynigsy.player.mpv

import io.mirr.shynigsy.player.RED_TONE_RANGE
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

internal const val PLEZY_NVSCALER_ID = "plezy/nvscaler"
private val sdrTransfers = setOf("bt.1886", "bt.709", "srgb", "gamma1.8", "gamma2.0", "gamma2.2", "gamma2.4", "gamma2.6", "gamma2.8", "prophoto")

/** Plezy skips NVScaler when decoded HDR colour information becomes available. Retain the requested
 * preset in preferences so a subsequent SDR title automatically restores it. */
internal fun effectivePlezyShader(requested: String?, property: (String) -> String?): String? {
    if (requested != PLEZY_NVSCALER_ID) return requested
    if (property("video-params/colormatrix")?.contains("bt.2020") == true ||
        property("video-params/primaries")?.contains("bt.2020") == true) return null
    val gamma = property("video-params/gamma").orEmpty()
    return requested.takeUnless { "pq" in gamma || "hlg" in gamma }
}

/** Require decoded SDR colour information before changing red. Metadata may arrive after FILE_LOADED. */
internal fun sdrRedTone(requested: Int, property: (String) -> String?): Int {
    if (requested == 0) return 0
    val gamma = property("video-params/gamma") ?: return 0
    if (gamma !in sdrTransfers) return 0
    if (property("video-params/primaries").orEmpty().contains("bt.2020") ||
        property("video-params/colormatrix").orEmpty().contains("bt.2020")) return 0
    return requested.coerceIn(RED_TONE_RANGE)
}

internal fun redToneShaderSource(redTone: Int): String {
    val amount = -redTone.coerceIn(RED_TONE_RANGE)
    return """//!HOOK MAIN
        |//!BIND HOOKED
        |//!DESC SDR red balance
        |vec4 hook() {
        |    vec4 c = HOOKED_tex(HOOKED_pos);
        |    c.r = clamp(c.r + ($amount.0 / 100.0) * (dot(c.gb, vec2(0.75, 0.25)) - c.r), 0.0, 1.0);
        |    return c;
        |}
    """.trimMargin()
}

/** All selection and native-option changes run on the player's main thread. Only asset I/O runs on the worker. */
internal class MpvShaderChain(
    private val directory: File,
    private val lookup: (String) -> List<String>,
    private val openAsset: (String) -> InputStream,
    capabilities: () -> MpvShaderGpu,
    private val post: (() -> Unit) -> Unit,
    private val setPaths: (List<File>) -> Unit,
    private val unavailable: (String) -> Unit,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private var library: List<File> = emptyList()
    private var tone: File? = null
    private var toneValue = 0
    private var tonePending: Future<*>? = null
    private var omitTone = false
    private var applied: List<File> = emptyList()
    private var requested: String? = null
    private var generation = 0L
    private var appliedRevision = 0L
    private val renderingGeneration = AtomicLong()
    private var pending: Future<*>? = null
    private var closed = false
    // Only this instance's single worker reads this map. A new APK starts a new process.
    private val extracted = mutableMapOf<String, File>()
    private val gpu by lazy(LazyThreadSafetyMode.NONE, capabilities)

    fun discardPreviousShaderCache(filesDir: File) {
        val oldLibrary = File(filesDir, "mpv-shader-library")
        val oldFilters = listOf("playback-denoise.glsl", "playback-denoise-1.glsl", "playback-denoise-2.glsl",
            "playback-denoise-3.glsl").map { File(filesDir, it) }
        if (!oldLibrary.exists() && oldFilters.none { it.exists() }) return
        worker.execute {
            oldFilters.forEach { if (it.isFile) it.delete() }
            if (!oldLibrary.isDirectory || Files.isSymbolicLink(oldLibrary.toPath())) return@execute
            val generatedName = Regex("""[0-9a-f]{64}\.glsl|shader-[0-9]+\.tmp""")
            oldLibrary.listFiles()?.forEach { file ->
                if (file.isFile && generatedName.matches(file.name)) file.delete()
            }
            oldLibrary.delete() // Succeeds only if the obsolete directory is empty.
        }
    }

    fun select(id: String?) {
        if (closed) return
        if (id != requested) {
            requested = id
            omitTone = false
            val request = ++generation
            pending?.cancel(true)
            library = emptyList()
            if (id != null) pending = worker.submit {
                val files = try {
                    lookup(id).also { require(it.isNotEmpty()) }.map { path ->
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        extracted.getOrPut(path) { extract(path) }
                    }
                } catch (_: InterruptedException) {
                    return@submit
                } catch (_: Exception) {
                    emptyList()
                }
                post {
                    if (!closed && generation == request) {
                        library = files
                        apply()
                        if (files.isEmpty()) unavailable(id)
                    }
                }
            }
        }
        apply()
    }

    fun setRedTone(value: Int) {
        if (closed || value == toneValue) return
        toneValue = value
        omitTone = false
        tonePending?.cancel(true)
        if (value == 0) {
            tone = null
            apply()
            return
        }
        tonePending = worker.submit {
            val file = try {
                extracted.getOrPut("red:$value") { store(redToneShaderSource(value).toByteArray(Charsets.UTF_8)) }
            } catch (_: IOException) { null }
            post {
                if (!closed && value == toneValue) {
                    tone = file
                    apply()
                }
            }
        }
    }

    private fun apply() {
        val paths = if (omitTone || tone == null) library else library + listOfNotNull(tone)
        if (applied != paths) {
            applied = paths
            appliedRevision++
            renderingGeneration.set(if (paths.isEmpty()) 0 else appliedRevision)
            setPaths(paths)
        }
    }

    fun close() {
        closed = true
        renderingGeneration.set(0)
        pending?.cancel(true)
        tonePending?.cancel(true)
        worker.shutdownNow()
    }

    /** Native renderer errors arrive on the JNI event thread. A newer selection always wins. */
    fun rendererFailed() {
        val ticket = renderingGeneration.get()
        if (ticket == 0L || !renderingGeneration.compareAndSet(ticket, 0)) return
        post {
            val id = requested
            if (!closed && appliedRevision == ticket) {
                if (!omitTone && tone != null) {
                    // An anonymous native error can belong to either hook. Retry the library alone
                    // before reporting a bad preset, including when only the SDR balance failed.
                    omitTone = true
                    apply()
                } else if (library.isNotEmpty() && id != null) {
                    library = emptyList()
                    omitTone = false
                    apply()
                    unavailable(id)
                }
            }
        }
    }

    private fun extract(path: String): File {
        require(path.startsWith("shaders/plezy/") && path.endsWith(".glsl"))
        require(path.none { it == '\\' || it == ':' || it.isISOControl() })
        require(path.split('/').all { it.isNotBlank() && it != "." && it != ".." })
        val original = openAsset(path).use { it.readBytes() }
        require(original.isNotEmpty())
        val raw = original.toString(Charsets.UTF_8)
        val source = when {
            path.startsWith("shaders/plezy/artcnn/") -> plezyArtcnnGles(path, raw)
            path == "shaders/plezy/nvscaler/NVScaler.glsl" -> plezyNvScalerGles(raw)
            else -> raw
        }
        val bytes = if (source === raw) original else
            ("// Android OpenGL ES adaptation, 2026-11-24. Original notices follow.\n" + source).toByteArray(Charsets.UTF_8)
        if (source.contains("//!COMPUTE") || source.contains("//!TEXTURE") || source.contains("#extension") || source.contains("textureGather")) {
            gpu.checkRequirements(source)
        }
        return store(bytes)
    }

    private fun store(bytes: ByteArray): File {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Shader directory")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val target = File(directory, "$digest.glsl")
        if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) return target
        val temporary = File.createTempFile("shader-", ".tmp", directory)
        try {
            temporary.writeBytes(bytes)
            try {
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        return target
    }
}

internal data class MpvShaderGpu(
    val esMajor: Int = 0,
    val maxGroupX: Int = 0,
    val maxGroupY: Int = 0,
    val maxInvocations: Int = 0,
    val maxTextureSize: Int = 0,
    val max3dTextureSize: Int = 0,
    val extensions: Set<String> = emptySet(),
) {
    fun checkRequirements(source: String) {
        // A guarded texture gather has a normal sampling fallback.
        if ("textureGather" in source && "ifdef HOOKED_gather" !in source && "defined(HOOKED_gather)" !in source) {
            require(maxInvocations > 0 || "GL_EXT_texture_gather" in extensions)
        }
        var textureFormat = ""
        var linear = true
        fun checkFilter() {
            if (linear && textureFormat.endsWith("32f")) require("GL_OES_texture_float_linear" in extensions)
            if (linear && textureFormat.endsWith("16f")) require(esMajor >= 3 || "GL_OES_texture_half_float_linear" in extensions)
        }
        for (line in source.lineSequence().map(String::trim)) {
            when {
                line.startsWith("//!TEXTURE ") -> {
                    checkFilter()
                    textureFormat = ""
                    linear = true
                }
                line.startsWith("//!COMPUTE ") -> {
                    val dimensions = line.substringAfter(' ').trim().split(whitespace).map(String::toInt)
                    require(dimensions.size == 2 || dimensions.size == 4)
                    val (x, y) = dimensions.takeLast(2)
                    require(x in 1..maxGroupX && y in 1..maxGroupY && x.toLong() * y <= maxInvocations)
                }
                line.startsWith("//!SIZE ") -> {
                    val dimensions = line.substringAfter(' ').trim().split(whitespace).map(String::toInt)
                    val limit = if (dimensions.size == 3) max3dTextureSize else maxTextureSize
                    require(dimensions.all { it in 1..limit })
                }
                line.startsWith("//!FORMAT ") -> {
                    val format = line.substringAfter(' ').trim().lowercase()
                    if (format.endsWith("32f")) require(esMajor >= 3 || "GL_OES_texture_float" in extensions)
                    if (format.endsWith("16f")) require(esMajor >= 3 || "GL_OES_texture_half_float" in extensions)
                    textureFormat = format
                }
                line.startsWith("//!FILTER ") -> linear = line.substringAfter(' ').trim().equals("LINEAR", ignoreCase = true)
                line.startsWith("#extension") -> requiredExtension.find(line)?.let {
                    require(it.groupValues[1] in extensions)
                }
            }
        }
        checkFilter()
    }

    private companion object {
        val whitespace = Regex("\\s+")
        val requiredExtension = Regex("""^#extension\s+(\w+)\s*:\s*require\b""")
    }
}
