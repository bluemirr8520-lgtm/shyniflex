package io.mirr.shynigsy.player.mpv

/**
 * Runs Plezy's NVIDIA Image Scaling 1.0.2 kernel as one fragment per output pixel.
 * The source file keeps the MIT license, fixed sharpening, directional filters,
 * 64-phase coefficient banks, and resolution predicate from the installed Plezy APK.
 */
internal fun plezyNvScalerGles(source: String): String {
    val header = source.substringBefore("// CS Shared variables")
        .replace(Regex("""//!COMPUTE 32 24 256 1\r?\n"""), "")
        .replace("clamp(x, 0, 1)", "clamp(x, 0.0, 1.0)")
        .replace("2 * 1127.f", "2.0 * 1127.f")
    val edge = source.substringAfter("vec4 GetEdgeMap(").substringBefore("void LoadFilterBanksSh(")
    val filters = source.substringAfter("float CalcLTI(").substringBefore("void hook()")
    val functions = ("vec4 GetEdgeMap(" + edge + "float CalcLTI(" + filters)
        // Local initializers depend on samples. GLSL ES requires constant expressions for const.
        .replace(Regex("""(?m)^(\s+)const (float|bool|int|vec4) """), "$1$2 ")
        // GLSL ES 3.0 only permits one-dimensional arrays.
        .replace("float p[4][4]", "float p[36]")
        .replace("float p[6][6]", "float p[36]")
        .replace(Regex("""\bp\[([^\]]+)]\[([^\]]+)]"""), "p[($1) * 6 + ($2)]")
        .replace("vec4 edge[2][2]", "vec4 edge[4]")
        .replace(Regex("""\bedge\[(\d)]\[(\d)]"""), "edge[$1 * 2 + $2]")
        .replace(Regex("""shCoef(Scaler|USM)\[(\w+)]\[(\w+)]""")) {
            val bank = if (it.groupValues[1] == "Scaler") "coef_scaler" else "coef_usm"
            val phase = it.groupValues[2]
            val tap = it.groupValues[3]
            "nisCoefficient($bank, $phase, $tap)"
        }
        .replace("float e_0_90 = 0;", "float e_0_90 = 0.0;")
        .replace("float e_45_135 = 0;", "float e_45_135 = 0.0;")
        .replace("g_45_135_max == 0)", "g_45_135_max == 0.0)")
        .replace(">= 1)", ">= 1.0)")
        .replace(" - 1;", " - 1.0;")
        .replace(" = 1 + (phase_", " = 1.0 + (phase_")
        .replace(" * 64)", " * 64.0)")
    val fragment = """
        vec4 hook() {
            // Round back to the destination pixel before computing the phase, as in
            // the original workgroup kernel. Reassociating the ratio can select an
            // adjacent filter-bank row exactly at a phase boundary.
            vec2 dst = floor(HOOKED_pos * target_size);
            vec2 scale = HOOKED_size / target_size;
            vec2 src = (dst + vec2(0.5)) * scale - vec2(0.5);
            vec2 base = floor(src);
            vec2 phase = src - base;
            ivec2 index = ivec2(phase * float(kPhaseCount));
            vec2 blockSize = vec2(NIS_BLOCK_WIDTH, NIS_BLOCK_HEIGHT);
            vec2 block = floor(dst / blockSize) * blockSize;
            vec2 blockSrc = floor((block + vec2(0.5)) * scale - vec2(0.5));
            // The compute tile starts two texels left and above floor(src).
            float p[36];
            for (int y = 0; y < 6; ++y) {
                for (int x = 0; x < 6; ++x) {
                    vec2 tap = base + vec2(x, y) - vec2(2.0);
                    // In Plezy's compute kernel, adding the unsigned 2x2 tile
                    // offset to a negative block origin wraps that pair to the
                    // far texture edge. Match these boundary samples explicitly.
                    if (blockSrc.x < 0.0 && tap.x < -1.0) tap.x = HOOKED_size.x - 1.0;
                    if (blockSrc.y < 0.0 && tap.y < -1.0) tap.y = HOOKED_size.y - 1.0;
                    p[y * 6 + x] = HOOKED_tex((tap + vec2(0.5)) * HOOKED_pt).r;
                }
            }
            vec4 edge[4];
            for (int y = 0; y < 2; ++y) {
                for (int x = 0; x < 2; ++x) {
                    edge[y * 2 + x] = GetEdgeMap(p, 1 + y, 1 + x);
                }
            }
            vec4 w = GetInterpEdgeMap(edge, phase.x, phase.y);
            float baseWeight = NIS_SCALE_FLOAT - w.x - w.y - w.z - w.w;
            float outY = FilterNormal(p, index.x, index.y) * baseWeight;
            outY += AddDirFilters(p, phase.x, phase.y, index.x, index.y, w);
            vec4 pixel = HOOKED_tex(HOOKED_pos);
            pixel.r = outY / NIS_SCALE_FLOAT;
            return pixel;
        }
    """.trimIndent()
    // mpv's GLES backend on Tegra does not expose rgba32f user textures. Store each
    // original little-endian float in an RGBA8 texel and recover its exact IEEE bits.
    val coefficient = """
        float nisCoefficient(highp sampler2D bank, int phase, int tap) {
            highp uvec4 b = uvec4(round(texelFetch(bank, ivec2(tap, phase), 0) * 255.0));
            return uintBitsToFloat(b.x | (b.y << 8u) | (b.z << 16u) | (b.w << 24u));
        }
    """.trimIndent()
    val textureMarker = "//!TEXTURE coef_scaler"
    val banks = (textureMarker + source.substringAfter(textureMarker))
        .replace("//!SIZE 2 64", "//!SIZE 8 64")
        .replace("//!FORMAT rgba32f", "//!FORMAT rgba8")
    return header + coefficient + "\n" + functions + fragment + "\n\n" + banks
}
