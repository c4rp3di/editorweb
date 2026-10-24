package com.perplexus.game.lifecycle

import com.perplexus.game.core.GameState
import com.perplexus.game.core.StateMachine

/** Pausa automática al perder el foco / pasar a segundo plano. */
class PauseController(private val machine: StateMachine) {
    var autoPaused = false
        private set

    /** Llamar desde onPause / pérdida de foco. Devuelve true si se pausó la partida. */
    fun onLeaving(): Boolean {
        if (machine.state == GameState.PLAYING) {
            autoPaused = machine.transition(GameState.PAUSED)
            return autoPaused
        }
        return false
    }

    fun onReturning() { autoPaused = false }
}
