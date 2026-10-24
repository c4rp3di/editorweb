package com.perplexus.game.physics

/** Parámetros por defecto (ver plan §6.8) y constantes de estabilidad. */
object PhysicsConstants {
    const val GRAVITY: Float = 9.81f
    const val FIXED_DT: Float = 1f / 240f
    const val MAX_FRAME_DT: Float = 0.1f
    const val MAX_STEPS_PER_UPDATE: Int = 24

    const val BALL_RADIUS: Float = 0.04f
    const val BALL_MASS: Float = 0.01f

    const val SOLVER_ITERATIONS: Int = 8
    const val BAUMGARTE_BETA: Float = 0.2f
    const val PENETRATION_SLOP: Float = 0.0004f
    const val MAX_CORRECTION: Float = 0.02f
    /** Por debajo de esta velocidad normal de impacto no hay rebote (evita vibración en reposo). */
    const val RESTITUTION_THRESHOLD: Float = 0.6f

    const val LINEAR_DAMPING: Float = 0.05f
    const val ANGULAR_DAMPING: Float = 0.1f

    const val MAX_SPEED: Float = 50f
    const val MAX_ANGULAR_SPEED: Float = 100f
    const val MAX_FRAME_ANGULAR_ACCEL: Float = 200f

    /** CCD por subdivisión: máximo desplazamiento por subpaso como fracción del radio. */
    const val CCD_MAX_MOVE_FRACTION: Float = 0.5f
    const val CCD_MAX_SUBSTEPS: Int = 16
    const val CCD_MIN_SPEED: Float = 5f

    /** Si la velocidad queda saturada este tiempo (s) se hace un reinicio de emergencia. */
    const val RUNAWAY_SECONDS: Float = 0.75f

    /** Incluye el acoplamiento -α_E - ω_E×ω del espín relativo de la bola (el plan lo omite). */
    const val SPIN_FRAME_COUPLING: Boolean = false

    const val MAX_CONTACTS: Int = 64
}
