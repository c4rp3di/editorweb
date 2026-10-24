package com.perplexus.game.render

import android.opengl.GLES30
import com.perplexus.game.diag.GameLog
import com.perplexus.game.math.Mat4
import com.perplexus.game.math.Vec3
import kotlin.math.abs

/** Mapa de sombras de la luz direccional (profundidad 1024²) con comparación por hardware. */
class ShadowMap(private val size: Int = RenderConstants.SHADOW_SIZE) {
    var fbo = 0; private set
    var depthTex = 0; private set
    @JvmField val lightVP = FloatArray(16)
    private val lightView = FloatArray(16)
    private val lightProj = FloatArray(16)
    var ok = false; private set

    fun create() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0); depthTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, size, size, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)

        GLES30.glGenFramebuffers(1, ids, 0); fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, depthTex, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        val st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        ok = st == GLES30.GL_FRAMEBUFFER_COMPLETE
        if (!ok) GameLog.w("ShadowMap", "FBO de sombras incompleto (0x${Integer.toHexString(st)}); sombras desactivadas")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Matriz luz: proyección ortográfica que abarca la esfera de radio r, mirando en la dirección de la luz. */
    fun updateLight(dir: Vec3, r: Float) {
        val d = dir.normalized()
        val upY = if (abs(d.y) > 0.95f) 0f else 1f
        val upZ = if (abs(d.y) > 0.95f) 1f else 0f
        val dist = r * 3f
        Mat4.lookAt(lightView, -d.x * dist, -d.y * dist, -d.z * dist, 0f, 0f, 0f, 0f, upY, upZ)
        val e = r * 1.15f
        Mat4.orthographic(lightProj, -e, e, -e, e, r * 0.5f, r * 5.5f)
        Mat4.multiply(lightVP, lightProj, lightView)
    }

    fun begin() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, size, size)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(2.0f, 4.0f)
        GLES30.glColorMask(false, false, false, false)
    }

    fun end(restoreFbo: Int, w: Int, h: Int) {
        GLES30.glColorMask(true, true, true, true)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, restoreFbo)
        GLES30.glViewport(0, 0, w, h)
    }

    fun texel(): Float = 1f / size

    fun release() {
        TextureLoader.delete(depthTex); depthTex = 0
        if (fbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0); fbo = 0 }
        ok = false
    }
}
