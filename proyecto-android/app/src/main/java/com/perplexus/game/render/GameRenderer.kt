package com.perplexus.game.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.perplexus.game.core.GameLoop
import com.perplexus.game.core.GameState
import com.perplexus.game.diag.DebugData
import com.perplexus.game.diag.FrameProfiler
import com.perplexus.game.diag.GameLog
import com.perplexus.game.levels.Level
import com.perplexus.game.math.Mat4
import com.perplexus.game.math.Quat
import com.perplexus.game.math.Vec3
import com.perplexus.game.physics.Outcome
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sin

/** Ciclo de dibujo (hilo GL): sensores → física → sombras → escena → post-proceso. */
class GameRenderer(
    private val loop: GameLoop,
    @JvmField val camera: Camera,
    private val light: Light,
    @JvmField val profiler: FrameProfiler,
    @JvmField val debug: DebugData
) : GLSurfaceView.Renderer {

    private var worldShader: Shader? = null
    private var shellShader: Shader? = null
    private var shadowShader: Shader? = null
    private var particleShader: Shader? = null
    private val scene = Scene()
    private val shadow = ShadowMap()
    @JvmField val post = PostProcess()
    @JvmField val particles = ParticleSystem()
    private var marbleTex = 0
    private var whiteTex = 0

    private var width = 1
    private var height = 1
    @Volatile private var glReady = false
    @Volatile private var pendingLevel: Level? = null
    @Volatile @JvmField var shadowsEnabled = true
    @Volatile @JvmField var postEnabled = true

    // Temporales (sin allocation por frame)
    private val rot3 = FloatArray(9)
    private val levelModel = FloatArray(16)
    private val ballLocal = FloatArray(16)
    private val ballModel = FloatArray(16)
    private val markerLocal = FloatArray(16)
    private val markerModel = FloatArray(16)
    private val normalMat = FloatArray(9)
    private val identityQ = Quat()
    private val ballPos = Vec3()
    private val ballQ = Quat()
    private var timeSec = 0f
    private var lastFrameNs = 0L

    // ------------------------------------------------------------------ API desde otros hilos
    /** Solicita cargar un nivel (cualquier hilo); se aplica en el hilo GL cuando hay contexto. */
    fun requestLevel(level: Level, glView: GLSurfaceView) {
        pendingLevel = level
        glView.queueEvent { applyPendingLevel() }
    }

    private fun applyPendingLevel() {
        val l = pendingLevel ?: return
        if (!glReady) return
        pendingLevel = null
        loop.world.setup(l.shapes, l.shell, l.start, l.goal, l.goalRadius, l.ballRadius, l.ballMass)
        loop.levelGravityScale = l.gravityScale
        scene.setLevel(l)
        camera.shellRadius = l.shellRadius
        shadow.updateLight(light.direction, l.shellRadius)
        particles.clear()
        loop.resetClock()
        GameLog.i("Render", "Nivel '${l.id}' cargado: ${l.shapes.size} formas, ${scene.glass?.indexCount ?: 0} índices de cristal")
    }

    /** Reinicia la bola del nivel actual (hilo GL). */
    fun resetBallOnGl() {
        loop.world.resetBall()
        particles.clear()
        loop.resetClock()
    }

    /** Ráfaga de partículas en el sistema E (llamar desde el hilo GL). */
    fun spawnImpact(x: Float, y: Float, z: Float, speed: Float) {
        val n = (4 + speed * 2f).toInt().coerceIn(4, 24)
        val len = kotlin.math.sqrt(x * x + y * y + z * z).coerceAtLeast(1e-4f)
        // normal aproximada: hacia el centro de la esfera
        particles.burst(x, y, z, -x / len, -y / len, -z / len, n, 0.25f + speed * 0.03f, 0.9f, 0.95f, 1f, 1f, 0.5f)
    }

    fun spawnVictory() {
        val g = loop.world.goalPosition()
        particles.burst(g.x, g.y, g.z, 0f, 0f, 0f, 120, 0.9f, 0.4f, 1f, 0.55f, 1.8f, 1.2f)
        particles.burst(g.x, g.y, g.z, 0f, 0f, 0f, 80, 0.6f, 1f, 0.9f, 0.4f, 1.5f, 1.0f)
    }

    // ------------------------------------------------------------------ Ciclo de vida GL
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GameLog.i("Render", "onSurfaceCreated: ${GLES30.glGetString(GLES30.GL_RENDERER)} / ${GLES30.glGetString(GLES30.GL_VERSION)}")
        glReady = false
        scene.forgetGl()
        worldShader = Shader("world", Shaders.WORLD_VERT, Shaders.WORLD_FRAG)
        shellShader = Shader("shell", Shaders.WORLD_VERT, Shaders.SHELL_FRAG)
        shadowShader = Shader("shadow", Shaders.SHADOW_VERT, Shaders.SHADOW_FRAG)
        particleShader = Shader("particles", Shaders.PARTICLE_VERT, Shaders.PARTICLE_FRAG)
        scene.createStatic()
        scene.uploadLevel()
        shadow.create()
        post.create()
        particles.create()
        val (px, n) = ProceduralMarble.get()
        marbleTex = TextureLoader.fromPixels(px, n, n, true)
        whiteTex = TextureLoader.white()
        GLES30.glClearColor(0.043f, 0.063f, 0.125f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        lastFrameNs = 0L
        glReady = true
        applyPendingLevel()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = maxOf(1, w); height = maxOf(1, h)
        GLES30.glViewport(0, 0, width, height)
        post.resize(width, height)
        GameLog.i("Render", "Superficie ${width}x$height")
    }

    override fun onDrawFrame(gl: GL10?) {
        profiler.begin(FrameProfiler.Section.FRAME)
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) * 1e-9f).coerceAtMost(0.1f)
        lastFrameNs = now
        timeSec += dt

        // 1. Sensores + física
        profiler.begin(FrameProfiler.Section.PHYSICS)
        loop.tick(now)
        particles.update(dt)
        profiler.end(FrameProfiler.Section.PHYSICS)

        // 2. Escena
        profiler.begin(FrameProfiler.Section.SCENE)
        val aspect = width.toFloat() / height.toFloat()
        camera.update(aspect)
        loop.estimator.displayRotation(rot3)
        Mat4.fromMat3(levelModel, rot3)
        loop.world.renderPosition(ballPos)
        loop.world.renderOrientation(ballQ)
        Mat4.trs(ballLocal, ballQ, ballPos.x, ballPos.y, ballPos.z, loop.world.ball.radius)
        Mat4.multiply(ballModel, levelModel, ballLocal)

        val useShadow = shadowsEnabled && shadow.ok && worldShader?.valid == true && shadowShader?.valid == true
        if (useShadow) renderShadowPass()

        val usePost = postEnabled && post.enabled && post.isReady
        if (usePost) post.begin() else { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); GLES30.glViewport(0, 0, width, height) }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        drawScene(useShadow)
        profiler.end(FrameProfiler.Section.SCENE)

        // 3. Post
        profiler.begin(FrameProfiler.Section.POST)
        if (usePost) post.end()
        profiler.end(FrameProfiler.Section.POST)

        profiler.end(FrameProfiler.Section.FRAME)
        profiler.endFrame()
        fillDebug()
    }

    // ------------------------------------------------------------------ Sombras
    private fun renderShadowPass() {
        val sh = shadowShader ?: return
        shadow.begin()
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        sh.use()
        sh.setMat4("uLightVP", shadow.lightVP)
        sh.setMat4("uModel", levelModel)
        scene.opaque?.draw()
        sh.setMat4("uModel", ballModel)
        scene.ball?.draw()
        shadow.end(0, width, height)
    }

    // ------------------------------------------------------------------ Escena principal
    private fun setCommon(s: Shader, useShadow: Boolean) {
        s.setMat4("uViewProj", camera.viewProj)
        s.setVec3("uCamPos", camera.eye.x, camera.eye.y, camera.eye.z)
        s.setVec3("uLightDir", light.direction.x, light.direction.y, light.direction.z)
        s.setVec3("uLightColor", light.color[0], light.color[1], light.color[2])
        s.setVec3("uAmbient", light.ambient[0], light.ambient[1], light.ambient[2])
        s.setVec3("uFillDir", light.fillDirection.x, light.fillDirection.y, light.fillDirection.z)
        s.setVec3("uFillColor", light.fillColor[0], light.fillColor[1], light.fillColor[2])
        s.setInt("uTex", 0)
        s.setInt("uShadow", 2)
        s.setInt("uUseShadow", if (useShadow) 1 else 0)
        s.setMat4("uLightVP", shadow.lightVP)
        s.setFloat("uShadowBias", 0.0012f)
        s.setFloat("uShadowTexel", shadow.texel())
    }

    private fun drawMesh(s: Shader, mesh: Mesh?, model: FloatArray, m: RenderMaterial, tex: Int, alphaMul: Float = 1f) {
        if (mesh == null) return
        s.setMat4("uModel", model)
        Mat4.normalMatrix(normalMat, model)
        s.setMat3("uNormalMat", normalMat)
        s.setVec4("uTint", m.tint[0], m.tint[1], m.tint[2], m.tint[3])
        s.setFloat("uSpec", m.specular)
        s.setFloat("uShin", m.shininess)
        s.setVec3("uEmissive", m.emissive[0], m.emissive[1], m.emissive[2])
        s.setFloat("uRim", m.rim)
        s.setFloat("uAlpha", m.alpha * alphaMul)
        s.setInt("uUseTex", if (tex != 0) 1 else 0)
        if (tex != 0) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex) }
        mesh.draw()
    }

    private val goalMat = RenderMaterial(tint = floatArrayOf(0.45f, 1f, 0.6f, 0.55f), specular = 0.8f, shininess = 48f, emissive = floatArrayOf(0.15f, 0.7f, 0.3f), rim = 0.6f)
    private val startMat = RenderMaterial(tint = floatArrayOf(0.5f, 0.75f, 1f, 0.25f), specular = 0.3f, shininess = 16f, emissive = floatArrayOf(0.05f, 0.15f, 0.3f))

    private fun drawScene(useShadow: Boolean) {
        val ws = worldShader
        if (ws == null || !ws.valid) return
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        ws.use()
        setCommon(ws, useShadow)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadow.depthTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        // Sólidos opacos y bola
        drawMesh(ws, scene.opaque, levelModel, RenderMaterial.TRACK_SOLID, 0)
        drawMesh(ws, scene.ball, ballModel, RenderMaterial.BALL, marbleTex)

        // Cristal y marcadores (mezcla alfa, sin escribir profundidad)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        val lvl = scene.level
        if (lvl != null) {
            val pulse = 1f + 0.10f * sin(timeSec * 4f)
            val g = lvl.goal
            Mat4.trs(markerLocal, identityQ, g.x, g.y, g.z, lvl.goalRadius * pulse)
            Mat4.multiply(markerModel, levelModel, markerLocal)
            drawMesh(ws, scene.marker, markerModel, goalMat, 0)
            val st = lvl.start
            Mat4.trs(markerLocal, identityQ, st.x, st.y, st.z, lvl.ballRadius * 1.3f)
            Mat4.multiply(markerModel, levelModel, markerLocal)
            drawMesh(ws, scene.marker, markerModel, startMat, 0)
        }
        drawMesh(ws, scene.glass, levelModel, RenderMaterial.TRACK_GLASS, 0)

        // Esfera exterior con Fresnel
        val ss = shellShader
        if (ss != null && ss.valid && scene.shell != null) {
            ss.use()
            ss.setMat4("uViewProj", camera.viewProj)
            ss.setVec3("uCamPos", camera.eye.x, camera.eye.y, camera.eye.z)
            ss.setVec3("uLightDir", light.direction.x, light.direction.y, light.direction.z)
            ss.setFloat("uAlpha", 1f)
            ss.setMat4("uModel", levelModel)
            Mat4.normalMatrix(normalMat, levelModel)
            ss.setMat3("uNormalMat", normalMat)
            scene.shell?.draw()
        }

        // Partículas (aditivas)
        val ps = particleShader
        if (ps != null && ps.valid && particles.activeCount > 0) {
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
            ps.use()
            ps.setMat4("uViewProj", camera.viewProj)
            ps.setMat4("uModel", levelModel)
            ps.setFloat("uScale", height * 0.5f / kotlin.math.tan(camera.fovY * 0.5f))
            particles.draw(ps)
        }
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    // ------------------------------------------------------------------ Depuración
    private fun fillDebug() {
        val d = debug
        val w = loop.world
        val b = w.ball
        d.fps = profiler.fps
        d.physicsMs = profiler.avgMs[FrameProfiler.Section.PHYSICS.ordinal]
        d.sceneMs = profiler.avgMs[FrameProfiler.Section.SCENE.ordinal]
        d.postMs = profiler.avgMs[FrameProfiler.Section.POST.ordinal]
        d.frameMs = profiler.avgMs[FrameProfiler.Section.FRAME.ordinal]
        d.px = b.position.x; d.py = b.position.y; d.pz = b.position.z
        d.vx = b.velocity.x; d.vy = b.velocity.y; d.vz = b.velocity.z
        d.wx = b.angularVelocity.x; d.wy = b.angularVelocity.y; d.wz = b.angularVelocity.z
        d.gx = loop.gravityDebug.x; d.gy = loop.gravityDebug.y; d.gz = loop.gravityDebug.z
        val q = loop.estimator.quaternion
        d.qw = q.w; d.qx = q.x; d.qy = q.y; d.qz = q.z
        d.contacts = w.lastContactCount
        d.solverIterations = w.iterations
        d.steps = w.lastStepCount
        d.shapes = w.shapeCount
        d.bvhNodes = w.bvhNodes
        d.gyroHz = loop.estimator.gyroHz; d.accelHz = loop.estimator.accelHz
        d.gyroSuspicious = loop.estimator.monitor.suspicious
        d.latencyMs = loop.estimator.lastLatencyMs
        d.particles = particles.activeCount
        d.emergencyResets = w.emergencyResets
        d.state = loop.machine.state.name + (if (w.outcome != Outcome.RUNNING) "/" + w.outcome.name else "")
    }

    fun releaseGl() {
        glReady = false
        scene.release(); shadow.release(); post.release(); particles.release()
        worldShader?.release(); shellShader?.release(); shadowShader?.release(); particleShader?.release()
        TextureLoader.delete(marbleTex); TextureLoader.delete(whiteTex)
        marbleTex = 0; whiteTex = 0
    }
}
