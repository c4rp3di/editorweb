package com.perplexus.game.render

import android.opengl.GLES30
import com.perplexus.game.diag.GameLog

/**
 * Post-proceso: la escena se dibuja a una textura; luego brillo (bloom, 1/4 de resolución, blur separable),
 * FXAA ligero y viñeteado en la pasada final a pantalla.
 */
class PostProcess {
    var enabled = true
    @JvmField var bloomStrength = 0.55f
    @JvmField var vignette = 0.55f
    @JvmField var fxaa = true

    private var width = 0; private var height = 0
    private var sceneFbo = 0; private var sceneTex = 0; private var sceneDepth = 0
    private val bloomFbo = IntArray(2); private val bloomTex = IntArray(2)
    private var bw = 0; private var bh = 0
    private var bright: Shader? = null
    private var blur: Shader? = null
    private var finalS: Shader? = null
    private var ready = false

    val isReady: Boolean get() = ready

    fun create() {
        bright = Shader("bright", Shaders.POST_VERT, Shaders.BRIGHT_FRAG)
        blur = Shader("blur", Shaders.POST_VERT, Shaders.BLUR_FRAG)
        finalS = Shader("final", Shaders.POST_VERT, Shaders.FINAL_FRAG)
        if (!(bright!!.valid && blur!!.valid && finalS!!.valid)) {
            GameLog.w("Post", "Shaders de post-proceso no disponibles; se desactiva")
            enabled = false
        }
        width = 0; height = 0; ready = false
    }

    private fun makeColorTex(w: Int, h: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun freeTargets() {
        TextureLoader.delete(sceneTex); sceneTex = 0
        if (sceneFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0); sceneFbo = 0 }
        if (sceneDepth != 0) { GLES30.glDeleteRenderbuffers(1, intArrayOf(sceneDepth), 0); sceneDepth = 0 }
        for (i in 0..1) {
            TextureLoader.delete(bloomTex[i]); bloomTex[i] = 0
            if (bloomFbo[i] != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(bloomFbo[i]), 0); bloomFbo[i] = 0 }
        }
    }

    fun resize(w: Int, h: Int) {
        if (w == width && h == height && ready) return
        freeTargets()
        width = w; height = h
        val ids = IntArray(1)
        sceneTex = makeColorTex(w, h)
        GLES30.glGenRenderbuffers(1, ids, 0); sceneDepth = ids[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, sceneDepth)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, w, h)
        GLES30.glGenFramebuffers(1, ids, 0); sceneFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, sceneTex, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, sceneDepth)
        var ok = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE

        bw = maxOf(1, w / 4); bh = maxOf(1, h / 4)
        for (i in 0..1) {
            bloomTex[i] = makeColorTex(bw, bh)
            GLES30.glGenFramebuffers(1, ids, 0); bloomFbo[i] = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, bloomTex[i], 0)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) ok = false
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        ready = ok
        if (!ok) { GameLog.w("Post", "FBOs de post-proceso incompletos; se desactiva"); enabled = false }
        else GameLog.i("Post", "Post-proceso listo ${w}x$h (bloom ${bw}x$bh)")
    }

    /** Debe llamarse antes de dibujar la escena. */
    fun begin() {
        if (!(enabled && ready)) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glViewport(0, 0, width, height)
    }

    private fun fullscreen() {
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /** Compone la escena en el framebuffer por defecto. */
    fun end() {
        if (!(enabled && ready)) return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(false)

        // 1) Brillos → bloomA (1/4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[0])
        GLES30.glViewport(0, 0, bw, bh)
        val b = bright!!; b.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)
        b.setInt("uScene", 0)
        fullscreen()

        // 2) Blur separable (H: A→B, V: B→A), dos iteraciones
        val bl = blur!!; bl.use(); bl.setInt("uTex", 0)
        for (it in 0 until 2) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[1])
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTex[0])
            bl.setVec2("uDir", 1f / bw, 0f)
            fullscreen()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[0])
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTex[1])
            bl.setVec2("uDir", 0f, 1f / bh)
            fullscreen()
        }

        // 3) Composición final
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        val f = finalS!!; f.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTex[0])
        f.setInt("uScene", 0); f.setInt("uBloom", 1)
        f.setVec2("uTexel", 1f / width, 1f / height)
        f.setFloat("uBloomStrength", bloomStrength)
        f.setFloat("uVignette", vignette)
        f.setInt("uFxaa", if (fxaa) 1 else 0)
        fullscreen()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    fun release() {
        freeTargets()
        bright?.release(); blur?.release(); finalS?.release()
        bright = null; blur = null; finalS = null
        ready = false; width = 0; height = 0
    }
}
