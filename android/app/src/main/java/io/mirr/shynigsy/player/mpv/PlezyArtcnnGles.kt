package io.mirr.shynigsy.player.mpv

private val artcnnAsset = Regex("""shaders/plezy/artcnn/ArtCNN_C4F(?:16|32)(?:_DN|_DS)?\.glsl""")
private val inputStride = Regex("""input_base = .*? \* (ivec2\(\d+, \d+\));""")
private val outputStride = Regex("""output_base = .*? \* (ivec2\((\d+), (\d+)\));""")
private val inputPlane = Regex("""inp\[(\d+)]\[y]\[x] = ([^\r\n]+);""")
private val inputTap = Regex("""const (F|V4) (inp_(\d+)_(\d+)_(\d+)) = inp\[""")
private val calculation = Regex("""(?m)^[ \t]*((?:V4 )?result(\d+) (?:=|\+=) [^\r\n]+;)""")
private val outputPlane = Regex("""imageStore\(out_image, output_base \+ ivec2\((\d+), (\d+)\), ([^\r\n]+)\);""")
private val sampler = Regex("""(\w+)_mul \* texelFetch\(\1_raw, input_base \+ ivec2\(\d+, \d+\), 0\)""")

/** Reuse the bundled network's biases, weights, tap order, residuals and shuffle. The GLES fragment
 * pass writes the packed feature texel directly, avoiding imageStore on mpv's mutable GLES textures. */
internal fun plezyArtcnnGles(assetPath: String, source: String): String {
    if (!artcnnAsset.matches(assetPath) || "//!COMPUTE " !in source) return source
    val passes = source.split("//!DESC ")
    return buildString(source.length) {
        append(passes.first())
        for (stage in passes.drop(1)) {
            val metadata = stage.substringBefore("#extension")
            val stride = outputStride.find(stage)
            val headers = ("//!DESC " + metadata).lineSequence().filter { it.startsWith("//!") && !it.startsWith("//!COMPUTE ") }.map {
                // The depth shuffle overwrites the native single-channel LUMA target.
                if (stride == null && it == "//!COMPONENTS 4") "//!COMPONENTS 1" else it
            }.toList()
            if (stride == null) {
                // The final depth-to-space pass already looks up one packed component per output pixel.
                require("(Depth-To-Space)" in metadata)
                headers.forEach { appendLine(it) }
                appendLine("vec4 hook() {")
                append(stage.substringAfter("void hook() {").replace(
                    "imageStore(out_image, ivec2(gl_GlobalInvocationID), clamp(result, 0.0, 1.0));",
                    "return clamp(result, 0.0, 1.0);"))
                continue
            }
            val packing = stride.groupValues[1]
            val width = stride.groupValues[2].toInt()
            val height = stride.groupValues[3].toInt()
            val step = requireNotNull(inputStride.find(stage)).groupValues[1]
            val reads = inputPlane.findAll(stage).associate { it.groupValues[1] to it.groupValues[2] }
            val operations = calculation.findAll(stage).groupBy({ it.groupValues[2].toInt() }, { it.groupValues[1] })
            val outputs = outputPlane.findAll(stage).associate {
                it.groupValues[1].toInt() + width * it.groupValues[2].toInt() to it.groupValues[3]
            }
            require(outputs.size == width * height && operations.keys == outputs.keys && reads.isNotEmpty())
            if ("ArtCNN_C4F32" in assetPath) {
                appendArtcnnFeaturePlanes(headers, reads, operations, outputs, stage)
                continue
            }
            headers.forEach { appendLine(it) }
            appendLine("#define F float\n#define V4 vec4\n#define M4 mat4")
            appendLine("vec4 hook() {")
            appendLine("    ivec2 pixel = ivec2(gl_FragCoord.xy);")
            appendLine("    ivec2 base = pixel / $packing;")
            for (match in inputTap.findAll(stage)) {
                val (type, name, plane, x, y) = match.destructured
                val coordinate = "((base + ivec2(" + (x.toInt() - 1) + ", " + (y.toInt() - 1) + ")) * $step)"
                appendLine("    $type $name = " + reads.getValue(plane).replace("input_base", coordinate) + ";")
            }
            if (outputs.size > 1) {
                appendLine("    ivec2 sub = pixel - base * $packing;")
                appendLine("    int channel = sub.x + sub.y * $width;")
            }
            for ((channel, expression) in outputs) {
                val conditional = channel < outputs.size - 1
                if (conditional) appendLine("    if (channel == $channel) {")
                operations.getValue(channel).forEach { appendLine("    $it") }
                appendLine("    return $expression;")
                if (conditional) appendLine("    }")
            }
            appendLine("}")
        }
    }
}

private fun StringBuilder.appendArtcnnFeaturePlanes(headers: List<String>, reads: Map<String, String>,
    operations: Map<Int, List<String>>, outputs: Map<Int, String>, stage: String) {
    val saved = headers.single { it.startsWith("//!SAVE ") }.substringAfter(' ')
    val dependencies = headers.filter { it.startsWith("//!BIND ") }.map { it.substringAfter(' ') }
    val trigger = headers.filter { it.startsWith("//!HOOK ") || it.startsWith("//!WHEN ") }
    val description = headers.first()
    fun name(layer: String, plane: String) = if (layer == "LUMA") layer else "art32_" + layer + "_" + plane
    fun fetch(layer: String) = layer + "_mul * texelFetch(" + layer + "_raw, coord, 0)"
    fun header(title: String, target: String, binds: List<String>) {
        appendLine(title)
        trigger.forEach { appendLine(it) }
        binds.distinct().forEach { appendLine("//!BIND $it") }
        appendLine("//!SAVE $target\n//!WIDTH LUMA.w\n//!HEIGHT LUMA.h\n//!COMPONENTS 4")
    }
    // The last convolution reads two tensors per tap. Sum the matching feature planes first,
    // keeping each subsequent pass within eight samplers on mobile fragment hardware.
    val residual = dependencies.size == 2
    if (residual) for (plane in reads.keys) {
        val inputs = dependencies.map { name(it, plane) }
        header("$description residual $plane", name("residual", plane), inputs)
        appendLine("vec4 hook() { ivec2 coord = ivec2(gl_FragCoord.xy); return " + inputs.joinToString(" + ", transform = ::fetch) + "; }")
    }
    val parents = reads.keys.map { name(if (residual) "residual" else dependencies.single(), it) }
    val taps = buildString {
        for (match in inputTap.findAll(stage)) {
            val (type, variable, plane, x, y) = match.destructured
            val coord = "(base + ivec2(" + (x.toInt() - 1) + ", " + (y.toInt() - 1) + "))"
            val expression = if (residual) "V4(" + fetch(name("residual", plane)) + ")"
                else reads.getValue(plane).replace(sampler) { fetch(name(it.groupValues[1], plane)) }
            appendLine("    $type $variable = " + expression.replace("coord", coord) + ";")
        }
    }
    for ((channel, expression) in outputs) {
        val target = if (outputs.size == 1) saved else name(saved, channel.toString())
        header(if (channel == 0) description else "$description plane $channel", target, parents)
        appendLine("#define F float\n#define V4 vec4\n#define M4 mat4")
        appendLine("vec4 hook() {\n    ivec2 base = ivec2(gl_FragCoord.xy);")
        append(taps)
        operations.getValue(channel).forEach { appendLine("    $it") }
        appendLine("    return $expression;\n}")
    }
}
