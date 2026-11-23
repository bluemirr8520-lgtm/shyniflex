package io.mirr.shynigsy.player.mpv

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31

/** Runs on the shader worker, using its own tiny EGL surface. The playback render thread keeps its current context. */
internal val mpvShaderGpu: MpvShaderGpu by lazy { queryMpvShaderGpu() }

private fun queryMpvShaderGpu(): MpvShaderGpu {
    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val version = IntArray(2)
    if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) return MpvShaderGpu()
    var context: EGLContext = EGL14.EGL_NO_CONTEXT
    var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    try {
        for (major in 3 downTo 2) {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val renderable = if (major == 3) 0x40 else EGL14.EGL_OPENGL_ES2_BIT
            val attributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, renderable,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) continue
            val config = configs[0] ?: continue
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, major, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT) continue
            surface = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) break
            fun limit(name: Int): Int = IntArray(1).also { GLES20.glGetIntegerv(name, it, 0) }[0]
            fun group(axis: Int): Int = IntArray(1).also {
                GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, axis, it, 0)
            }[0]
            val extensions = if (major >= 3) (0 until limit(GLES30.GL_NUM_EXTENSIONS)).mapNotNull {
                GLES30.glGetStringi(GLES20.GL_EXTENSIONS, it)
            }.toSet() else GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().split(' ').toSet()
            val compute = major >= 3 && limit(GLES30.GL_MINOR_VERSION) >= 1
            return MpvShaderGpu(major, if (compute) group(0) else 0, if (compute) group(1) else 0,
                if (compute) limit(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS) else 0,
                limit(GLES20.GL_MAX_TEXTURE_SIZE), if (major >= 3) limit(GLES30.GL_MAX_3D_TEXTURE_SIZE) else 0, extensions)
        }
        return MpvShaderGpu()
    } finally {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        // EGL_DEFAULT_DISPLAY is shared with libmpv. Hold this single process-wide initialization
        // until process exit; terminating the display could tear down the playing video's contexts.
        EGL14.eglReleaseThread()
    }
}
