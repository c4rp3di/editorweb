package com.perplexus.game

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.perplexus.game.audio.AudioManager
import com.perplexus.game.audio.Haptics
import com.perplexus.game.core.GameEvents
import com.perplexus.game.core.GameLoop
import com.perplexus.game.core.GameState
import com.perplexus.game.core.Session
import com.perplexus.game.core.StateMachine
import com.perplexus.game.diag.CrashHandler
import com.perplexus.game.diag.DebugData
import com.perplexus.game.diag.FrameProfiler
import com.perplexus.game.diag.GameLog
import com.perplexus.game.diag.LogViewer
import com.perplexus.game.input.Calibrator
import com.perplexus.game.input.OrientationEstimator
import com.perplexus.game.levels.AssetLevelSource
import com.perplexus.game.levels.Level
import com.perplexus.game.levels.LevelGenerator
import com.perplexus.game.levels.LevelLoader
import com.perplexus.game.lifecycle.GameLifecycle
import com.perplexus.game.lifecycle.PauseController
import com.perplexus.game.physics.PhysicsWorld
import com.perplexus.game.render.Camera
import com.perplexus.game.render.GameRenderer
import com.perplexus.game.render.Light
import com.perplexus.game.render.ProceduralMarble
import com.perplexus.game.storage.Prefs
import com.perplexus.game.ui.CalibrationScreen
import com.perplexus.game.ui.DefeatScreen
import com.perplexus.game.ui.GestureHandler
import com.perplexus.game.ui.MainMenuScreen
import com.perplexus.game.ui.PauseScreen
import com.perplexus.game.ui.RootLayout
import com.perplexus.game.ui.SettingsScreen
import com.perplexus.game.ui.TouchRouter
import com.perplexus.game.ui.VictoryScreen
import java.io.File

/**
 * Orquestación: cablea sensores, física, render y UI; gestiona los estados globales y las pantallas.
 * Todo lo que toca vistas ocurre en el hilo de UI; los eventos del hilo GL se reenvían con Handler.post.
 */
class AppLogic(private val activity: Activity) : GameEvents, StateMachine.Listener, GestureHandler.Listener {
    private val ui = Handler(Looper.getMainLooper())
    val machine = StateMachine(GameState.CALIBRATING)
    private val session = Session()
    private val world = PhysicsWorld()
    private val debugData = DebugData()
    private val profiler = FrameProfiler()
    private val camera = Camera()
    private val light = Light()

    private lateinit var lifecycle: GameLifecycle
    private lateinit var estimator: OrientationEstimator
    private lateinit var loop: GameLoop
    private lateinit var renderer: GameRenderer
    private lateinit var root: RootLayout
    private lateinit var haptics: Haptics
    private lateinit var pauseCtl: PauseController

    private var levels: List<Level> = emptyList()
    private var problems: List<String> = emptyList()
    private var calibScreen: CalibrationScreen? = null
    private var logViewer: LogViewer? = null
    private var pendingLevelIndex = -1
    private var pendingRandom: Level? = null
    private var resumeAfterCalibration = false
    private var returnState = GameState.MENU
    private var calibStartMs = 0L
    private var calibrated = false

    // ------------------------------------------------------------------ Ciclo de vida de la Activity
    fun onCreate() {
        GameLog.init(File(activity.filesDir, "game.log"))
        CrashHandler.install(activity.filesDir)
        Prefs.init(activity)
        AudioManager.init(activity)
        AudioManager.setEnabled(Prefs.getSoundEnabled())
        ProceduralMarble.pregenerate()
        GameLog.i("App", "Perplexus 3D v1.0.0 — SDK ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}")

        haptics = Haptics(activity).also { it.enabled = Prefs.getVibrationEnabled() }
        lifecycle = GameLifecycle(activity)
        estimator = OrientationEstimator(lifecycle.accelRing, lifecycle.gyroRing)
        estimator.setBias(Prefs.getCalibrationBias())
        loop = GameLoop(world, estimator, session, machine)
        loop.events = this
        loop.sensitivity = Prefs.getSensitivity()
        pauseCtl = PauseController(machine)

        loadLevels()

        renderer = GameRenderer(loop, camera, light, profiler, debugData)
        root = RootLayout(activity, { onPausePressed() }, debugData)
        root.glView.setRenderer(renderer)
        root.glView.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
        val gestures = GestureHandler(activity, this)
        root.glView.setOnTouchListener(TouchRouter(root.overlay, gestures))
        activity.setContentView(root)
        root.hud.visibility = View.GONE
        if (Build.VERSION.SDK_INT >= 28) {
            val attrs = activity.window.attributes
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            activity.window.attributes = attrs
        }
        applyImmersive()

        // Nivel de fondo para que la esfera se vea (y gire con el móvil) detrás de los menús
        if (levels.isNotEmpty()) renderer.requestLevel(levels[0], root.glView)

        machine.addListener(this)
        enter(GameState.MENU, GameState.CALIBRATING)
        checkLastCrash()
    }

    fun onResume() {
        root.glView.onResume()
        lifecycle.start()
        root.glView.queueEvent { estimator.onSensorsRestarted() }
        pauseCtl.onReturning()
        applyImmersive()
    }

    fun onPause() {
        pauseCtl.onLeaving()
        root.glView.onPause()
        lifecycle.stop()
        AudioManager.stopRoll()
        haptics.cancel()
    }

    fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        lifecycle.destroy()
        AudioManager.release()
        GameLog.close()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) applyImmersive() else pauseCtl.onLeaving()
    }

    fun onBackPressed() {
        when (machine.state) {
            GameState.PLAYING -> machine.transition(GameState.PAUSED)
            GameState.PAUSED, GameState.MENU -> activity.finish()
            GameState.CALIBRATING -> if (resumeAfterCalibration) { resumeAfterCalibration = false; machine.transition(GameState.PAUSED) } else activity.finish()
            GameState.WON, GameState.LOST -> machine.transition(GameState.MENU)
            GameState.SETTINGS, GameState.LOG_VIEWER -> machine.transition(returnState)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        activity.window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    // ------------------------------------------------------------------ Niveles
    private fun loadLevels() {
        val report = LevelLoader.loadAll(AssetLevelSource(activity))
        levels = report.levels
        problems = report.problems
        GameLog.i("App", "Niveles cargados: ${levels.size}, con problemas: ${problems.size}")
        for (p in problems) GameLog.w("Levels", p)
    }

    private fun startLevel(index: Int, custom: Level? = null) {
        val l = custom ?: levels.getOrNull(index) ?: return
        val best = if (index >= 0) Prefs.getBestTime(l.id) else 0L
        session.startAttempt(l, index, best)
        loop.sensitivity = Prefs.getSensitivity()
        renderer.requestLevel(l, root.glView)
        root.hud.setLevelName(l.name)
        root.hud.setBest(best)
        root.hud.setTime(0)
        GameLog.i("App", "Inicio de nivel '${l.id}' (índice $index)")
        if (machine.state != GameState.PLAYING) machine.transition(GameState.PLAYING)
    }

    private fun restartLevel() {
        session.restartAttempt()
        root.glView.queueEvent { renderer.resetBallOnGl() }
        root.hud.setTime(0)
        if (machine.state != GameState.PLAYING) machine.transition(GameState.PLAYING)
    }

    private fun requestStart(index: Int, random: Level? = null) {
        if (!calibrated) {
            pendingLevelIndex = index; pendingRandom = random
            machine.transition(GameState.CALIBRATING)
        } else startLevel(index, random)
    }

    // ------------------------------------------------------------------ Máquina de estados → pantallas
    override fun onStateChanged(from: GameState, to: GameState) {
        if (Looper.myLooper() != Looper.getMainLooper()) { ui.post { enter(from, to) }; return }
        enter(from, to)
    }

    private fun enter(from: GameState, to: GameState) {
        val gameVisible = to == GameState.PLAYING || to == GameState.PAUSED || to == GameState.WON || to == GameState.LOST
        root.hud.visibility = if (gameVisible) View.VISIBLE else View.GONE
        if (to != GameState.PLAYING) AudioManager.stopRoll()
        when (to) {
            GameState.CALIBRATING -> showCalibration()
            GameState.PLAYING -> {
                root.overlay.hide()
                root.glView.queueEvent { loop.resetClock() }
                ui.removeCallbacks(hudTick); ui.post(hudTick)
            }
            GameState.PAUSED -> showPause()
            GameState.WON -> showVictory()
            GameState.LOST -> showDefeat()
            GameState.SETTINGS -> { returnState = if (from == GameState.PAUSED) GameState.PAUSED else GameState.MENU; showSettings() }
            GameState.LOG_VIEWER -> { returnState = if (from == GameState.PAUSED) GameState.PAUSED else GameState.MENU; showLog() }
            GameState.MENU -> showMenu()
        }
    }

    private val hudTick = object : Runnable {
        override fun run() {
            if (machine.state != GameState.PLAYING) return
            root.hud.setTime(session.elapsedMs)
            ui.postDelayed(this, 100L)
        }
    }

    // ------------------------------------------------------------------ Pantallas
    private fun showMenu() {
        val menu = MainMenuScreen(
            activity, levels, problems,
            onLevel = { requestStart(it) },
            onRandom = { d ->
                val l = LevelGenerator.generate(System.currentTimeMillis(), d)
                GameLog.i("App", "Nivel aleatorio generado: ${l.id}")
                requestStart(-1, l)
            },
            onSettings = { machine.transition(GameState.SETTINGS) },
            onLog = { machine.transition(GameState.LOG_VIEWER) },
            onExit = { activity.finish() }
        )
        root.overlay.show(menu.view, scroll = true)
    }

    private fun showPause() {
        val s = PauseScreen(
            activity,
            onResume = { machine.transition(GameState.PLAYING) },
            onRestart = { restartLevel() },
            onRecalibrate = { resumeAfterCalibration = true; machine.transition(GameState.CALIBRATING) },
            onSettings = { machine.transition(GameState.SETTINGS) },
            onLog = { machine.transition(GameState.LOG_VIEWER) },
            onMenu = { machine.transition(GameState.MENU) },
            onExit = { activity.finish() }
        )
        root.overlay.show(s.view, scroll = true)
    }

    private fun showVictory() {
        val idx = session.levelIndex
        val hasNext = idx >= 0 && idx + 1 < levels.size
        val s = VictoryScreen(
            activity, session.elapsedMs, session.bestMs, session.newRecord, hasNext,
            onRepeat = { restartLevel() },
            onNext = { startLevel(idx + 1) },
            onMenu = { machine.transition(GameState.MENU) }
        )
        root.overlay.show(s.view)
    }

    private fun showDefeat() {
        val s = DefeatScreen(activity, onRetry = { restartLevel() }, onMenu = { machine.transition(GameState.MENU) })
        root.overlay.show(s.view)
    }

    private fun showSettings() {
        val s = SettingsScreen(
            activity, renderer.shadowsEnabled, renderer.postEnabled,
            onSound = { Prefs.setSoundEnabled(it); AudioManager.setEnabled(it) },
            onVibration = { Prefs.setVibrationEnabled(it); haptics.enabled = it },
            onSensitivity = { Prefs.setSensitivity(it); loop.sensitivity = it },
            onShadows = { renderer.shadowsEnabled = it },
            onPost = { renderer.postEnabled = it },
            onClearRecords = { Prefs.clearRecords(); Toast.makeText(activity, "Récords borrados", Toast.LENGTH_SHORT).show() },
            onBack = { machine.transition(returnState) }
        )
        root.overlay.show(s.view, scroll = true)
    }

    private fun showLog() {
        val v = LogViewer(activity) { machine.transition(returnState) }
        logViewer = v
        root.overlay.show(v.view)
    }

    // ------------------------------------------------------------------ Calibración
    private fun showCalibration() {
        val cs = CalibrationScreen(activity, onCalibrate = { beginCalibration() }, onBack = {
            if (resumeAfterCalibration) { resumeAfterCalibration = false; machine.transition(GameState.PAUSED) }
            else { pendingLevelIndex = -1; pendingRandom = null; machine.transition(GameState.MENU) }
        })
        calibScreen = cs
        if (!lifecycle.sensorsAvailable) {
            cs.setMessage(lifecycle.missingSensorMessage + " El juego no puede funcionar sin ellos.")
            cs.setProblem(true); cs.setBusy(true)
        }
        root.overlay.show(cs.view, scroll = true)
    }

    private fun beginCalibration() {
        val cs = calibScreen ?: return
        cs.setBusy(true); cs.setProblem(false); cs.setProgress(0f)
        cs.setMessage("Mantén el móvil quieto…")
        calibStartMs = System.currentTimeMillis()
        root.glView.queueEvent { estimator.startCalibration() }
        ui.postDelayed(calibrationPoll, 60L)
    }

    private val calibrationPoll = object : Runnable {
        override fun run() {
            val cs = calibScreen ?: return
            if (machine.state != GameState.CALIBRATING) return
            val c = estimator.calibrator
            when (c.state) {
                Calibrator.State.COLLECTING -> {
                    cs.setProgress(c.progress); cs.setMessage(c.message)
                    if (System.currentTimeMillis() - calibStartMs > 6000L && c.progress <= 0.001f) {
                        cs.setMessage("No llegan datos del giroscopio. Comprueba los permisos de sensores.")
                        cs.setProblem(true); cs.setBusy(false)
                        root.glView.queueEvent { estimator.calibrator.cancel() }
                        return
                    }
                    ui.postDelayed(this, 50L)
                }
                Calibrator.State.DONE -> {
                    cs.setProgress(1f); cs.setMessage(c.message)
                    val b = c.bias
                    Prefs.setCalibrationBias(floatArrayOf(b.x, b.y, b.z))
                    GameLog.i("Calib", "Sesgo giroscopio = $b")
                    root.glView.queueEvent {
                        estimator.applyCalibration()
                        ui.post { onCalibrationDone() }
                    }
                }
                Calibrator.State.FAILED -> {
                    cs.setMessage(c.message); cs.setProblem(true); cs.setBusy(false); cs.setProgress(0f)
                }
                Calibrator.State.IDLE -> ui.postDelayed(this, 50L)
            }
        }
    }

    private fun onCalibrationDone() {
        calibrated = true
        if (machine.state != GameState.CALIBRATING) return
        when {
            resumeAfterCalibration -> { resumeAfterCalibration = false; machine.transition(GameState.PLAYING) }
            pendingLevelIndex >= 0 || pendingRandom != null -> {
                val i = pendingLevelIndex; val r = pendingRandom
                pendingLevelIndex = -1; pendingRandom = null
                startLevel(i, r)
            }
            else -> machine.transition(GameState.MENU)
        }
    }

    // ------------------------------------------------------------------ Eventos del juego (hilo GL)
    override fun onImpact(speed: Float, x: Float, y: Float, z: Float) {
        AudioManager.playBounce(speed)
        haptics.impact(speed)
        renderer.spawnImpact(x, y, z, speed)
    }

    override fun onWon(timeMs: Long) {
        renderer.spawnVictory()
        ui.post {
            if (machine.state != GameState.PLAYING) return@post
            val idx = session.levelIndex
            val l = session.level
            if (idx >= 0 && l != null) {
                session.newRecord = Prefs.setBestTime(l.id, timeMs)
                session.bestMs = Prefs.getBestTime(l.id)
                Prefs.unlockLevel(idx + 1)
            } else {
                session.bestMs = timeMs; session.newRecord = false
            }
            AudioManager.playGoal(); haptics.victory()
            GameLog.i("App", "Victoria en ${Session.format(timeMs)} (récord=${session.newRecord})")
            machine.transition(GameState.WON)
        }
    }

    override fun onLost() {
        ui.post {
            if (machine.state != GameState.PLAYING) return@post
            AudioManager.playLose(); haptics.defeat()
            machine.transition(GameState.LOST)
        }
    }

    override fun onEmergencyReset(reason: String) {
        GameLog.w("Physics", "Reinicio de emergencia de la bola: $reason")
    }

    override fun onFrame(rollSpeed: Float) { AudioManager.updateRoll(rollSpeed) }

    // ------------------------------------------------------------------ Gestos
    override fun onOrbit(dYawRad: Float, dPitchRad: Float) { camera.orbit(dYawRad, dPitchRad) }
    override fun onZoom(factor: Float) { camera.zoomBy(factor) }
    override fun onRecenter() {
        camera.reset()
        root.glView.queueEvent { estimator.recenter() }
    }
    override fun onToggleDebug() { ui.post { root.debugOverlay.toggle() } }

    private fun onPausePressed() {
        if (machine.state == GameState.PLAYING) machine.transition(GameState.PAUSED)
    }

    // ------------------------------------------------------------------ Diagnóstico
    private fun checkLastCrash() {
        val f = CrashHandler.latestCrash(activity.filesDir) ?: return
        if (f.lastModified() <= Prefs.getLastCrashSeen()) return
        val text = try { f.readText().take(6000) } catch (_: Exception) { "(no se pudo leer ${f.name})" }
        AlertDialog.Builder(activity)
            .setTitle("La sesión anterior terminó con un error")
            .setMessage(text)
            .setPositiveButton("Cerrar") { _, _ -> Prefs.setLastCrashSeen(f.lastModified()) }
            .setOnCancelListener { Prefs.setLastCrashSeen(f.lastModified()) }
            .show()
    }
}
