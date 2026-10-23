package com.laberinto3d.juego.game.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.laberinto3d.juego.game.core.GameLoop
import com.laberinto3d.juego.game.core.GameState
import com.laberinto3d.juego.game.core.Session
import com.laberinto3d.juego.game.core.Telemetry
import com.laberinto3d.juego.game.diag.GameLog
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * Renderer de GLSurfaceView. En cada frame: actualiza el juego (GameLoop) y dibuja
 * suelo/paredes, meta, sombra y bola. Todo recurso de GL se recrea en onSurfaceCreated
 * (el contexto puede perderse al pausar la app).
 */
class GameRenderer(private val loop: GameLoop) : GLSurfaceView.Renderer {
    private val world = WorldRenderer()
    private val tris = TriMeshRenderer()
    private val ball = BallRenderer()
    private val shadow = ShadowRenderer()
    private val goal = GoalRenderer()
    private val camera = Camera()

    private var width = 1
    private var height = 1
    private var loadedSession: Session? = null
    private var glReady = false
    private var startNanos = 0L

    // luz direccional (hacia la luz) normalizada
    private val lx: Float
    private val ly: Float
    private val lz: Float

    // medidas de frames
    private var fpsFrames = 0
    private var fpsStart = 0L
    private var lastFrameNs = 0L
    private var frameMsSum = 0f
    private var renderNsSum = 0L
    private var renderFrames = 0

    init {
        val x = -0.35f
        val y = 0.85f
        val z = 0.45f
        val l = sqrt(x * x + y * y + z * z)
        lx = x / l; ly = y / l; lz = z / l
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Telemetry.glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "?"
        Telemetry.glRenderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "?"
        Telemetry.glVendor = GLES30.glGetString(GLES30.GL_VENDOR) ?: "?"
        Telemetry.glslVersion = GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION) ?: "?"
        GameLog.i("GL", "Contexto creado: ${Telemetry.glVersion} | ${Telemetry.glRenderer} | ${Telemetry.glVendor}")
        GameLog.i("GL", "GLSL: ${Telemetry.glslVersion}")
        if (!Telemetry.glVersion.contains("OpenGL ES 3")) {
            GameLog.e("GL", "La versión no es OpenGL ES 3.x: ${Telemetry.glVersion}")
        }
        world.destroy(); tris.destroy(); ball.destroy(); shadow.destroy(); goal.destroy()
        world.create()
        tris.create()
        ball.create()
        shadow.create()
        goal.create()
        loadedSession = null
        glReady = true
        startNanos = System.nanoTime()
        lastFrameNs = 0L
        GlUtil.check("onSurfaceCreated")
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = if (w > 0) w else 1
        height = if (h > 0) h else 1
        GLES30.glViewport(0, 0, width, height)
        Telemetry.surfaceW = width
        Telemetry.surfaceH = height
        GameLog.i("GL", "Surface ${width}x$height")
    }

    private fun aspect(): Float = width.toFloat() / height.toFloat()

    override fun onDrawFrame(gl: GL10?) {
        if (!glReady) return
        val t0 = System.nanoTime()
        val alpha = loop.update(t0)
        val s = GameState.currentSession

        GLES30.glClearColor(0.05f, 0.05f, 0.09f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (s != null) {
            if (s !== loadedSession) {
                world.setWorld(s.world)
                tris.setWorld(s.world)
                CameraControl.reset()
                loadedSession = s
                ball.interpolate(s.ball, 1f)
                camera.configure(s.world, aspect(), ball.drawX, ball.drawY, ball.drawZ)
                GameLog.i("GL", "Escena cargada: nivel ${s.levelId}, cámara a ${"%.2f".format(camera.fitDistance)} m, cajas ${s.world.boxes.size}")
            }
            CameraControl.clamp()
            ball.interpolate(s.ball, alpha)
            camera.configure(s.world, aspect(), ball.drawX, ball.drawY, ball.drawZ)
            Telemetry.camDistance = camera.distance
            drawScene(s, alpha, t0)
        }
        GlUtil.check("onDrawFrame")

        // medidas
        val t1 = System.nanoTime()
        renderNsSum += t1 - t0
        renderFrames++
        if (lastFrameNs != 0L) frameMsSum += (t0 - lastFrameNs) / 1e6f
        lastFrameNs = t0
        fpsFrames++
        if (fpsStart == 0L) fpsStart = t0
        if (t0 - fpsStart >= 1_000_000_000L) {
            val secs = (t0 - fpsStart) / 1e9f
            Telemetry.fps = fpsFrames / secs
            Telemetry.frameMsAvg = if (fpsFrames > 0) frameMsSum / fpsFrames else 0f
            Telemetry.renderMsAvg = if (renderFrames > 0) renderNsSum / renderFrames / 1e6f else 0f
            fpsFrames = 0; frameMsSum = 0f; renderNsSum = 0L; renderFrames = 0
            fpsStart = t0
        }
    }

    private fun drawScene(s: Session, alpha: Float, nowNs: Long) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glDisable(GLES30.GL_BLEND)

        world.updateMovers(s.world)
        world.draw(camera, lx, ly, lz, AMBIENT)
        tris.draw(camera, lx, ly, lz, AMBIENT)

        // decales (meta y sombra): sin culling, con mezcla, sin escribir profundidad
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(-2f, -2f)
        val gp = s.world.goalPosition
        val timeSec = (nowNs - startNanos) / 1e9f
        val cps = s.world.checkpoints
        for (i in cps.indices) {
            val c = cps[i]
            val done = i <= s.physics.checkpointIndex
            goal.draw(camera, c.x, c.y, c.z, com.laberinto3d.juego.game.core.Config.CHECKPOINT_RADIUS * 0.85f, timeSec,
                if (done) GoalRenderer.CP_DONE_A else GoalRenderer.CP_PENDING_A,
                if (done) GoalRenderer.CP_DONE_B else GoalRenderer.CP_PENDING_B,
                if (done) 0f else 1f)
        }
        goal.draw(camera, gp.x, gp.y, gp.z, s.world.goalRadius, timeSec)
        ball.interpolate(s.ball, alpha)
        val fy = s.world.floorBelow(ball.drawX, ball.drawZ, ball.drawY - s.ball.radius)
        if (fy > com.laberinto3d.juego.game.physics.World.NO_FLOOR) {
            shadow.draw(camera, ball.drawX, fy, ball.drawZ, s.ball.radius, ball.drawY - s.ball.radius - fy)
        }
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_CULL_FACE)

        ball.draw(s.ball, alpha, camera, lx, ly, lz, AMBIENT)
    }

    companion object {
        private const val AMBIENT = 0.42f
    }
}
