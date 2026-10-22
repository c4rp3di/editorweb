package com.laberinto3d.juego.game.render

import android.opengl.GLES30

/** Marca de la meta: disco con anillo que pulsa suavemente. No tiene colisión. */
class GoalRenderer {
    private var shader: Shader? = null
    private var quad: Mesh? = null

    fun create() {
        shader = Shader("goal", Shaders.QUAD_VERT, Shaders.GOAL_FRAG)
        quad = Mesh.quad()
    }

    fun draw(cam: Camera, gx: Float, gz: Float, radius: Float, timeSec: Float) {
        val s = shader ?: return
        val q = quad ?: return
        s.use()
        s.setMat4("uViewProj", cam.viewProj)
        s.setVec4("uCenterRadius", gx, 0.002f, gz, radius)
        s.setFloat("uTime", timeSec)
        q.draw(GLES30.GL_TRIANGLE_STRIP)
        GLES30.glBindVertexArray(0)
    }

    fun destroy() {
        shader?.delete(); shader = null
        quad?.delete(); quad = null
    }
}
