package com.perplexus.game.render

import com.perplexus.game.levels.Level

/** Recursos de malla de la escena actual (nivel + esfera exterior + bola + marcadores). */
class Scene {
    var opaque: Mesh? = null; private set
    var glass: Mesh? = null; private set
    var shell: Mesh? = null; private set
    var ball: Mesh? = null; private set
    var marker: Mesh? = null; private set
    var level: Level? = null; private set
    private var cachedLevelMeshes: LevelMesh.Result? = null

    /** Crea las mallas independientes del nivel (requiere contexto GL). */
    fun createStatic() {
        ball?.release(); marker?.release()
        ball = Mesh(BallMesh.build())
        marker = Mesh(SphereMesh.build(1f, 20, 10))
    }

    /** Construye y sube las mallas de un nivel (hilo GL). */
    fun setLevel(l: Level) {
        level = l
        cachedLevelMeshes = LevelMesh.build(l)
        uploadLevel()
    }

    /** Vuelve a subir tras pérdida de contexto GL. */
    fun uploadLevel() {
        val l = level ?: return
        val r = cachedLevelMeshes ?: LevelMesh.build(l).also { cachedLevelMeshes = it }
        opaque?.release(); glass?.release(); shell?.release()
        opaque = if (r.opaque.isEmpty()) null else Mesh(r.opaque)
        glass = if (r.glass.isEmpty()) null else Mesh(r.glass)
        shell = Mesh(ShellMesh.build(l.shellRadius))
    }

    /** Descarta handles GL (el contexto ya se perdió). */
    fun forgetGl() { opaque = null; glass = null; shell = null; ball = null; marker = null }

    fun release() {
        opaque?.release(); glass?.release(); shell?.release(); ball?.release(); marker?.release()
        opaque = null; glass = null; shell = null; ball = null; marker = null
    }
}
