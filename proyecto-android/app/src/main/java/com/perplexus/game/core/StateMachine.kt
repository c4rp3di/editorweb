package com.perplexus.game.core

import com.perplexus.game.diag.GameLog

/** Máquina de estados con transiciones válidas (plan §11.2) y observadores. */
class StateMachine(initial: GameState = GameState.MENU) {
    interface Listener { fun onStateChanged(from: GameState, to: GameState) }

    @Volatile var state: GameState = initial
        private set
    private val listeners = ArrayList<Listener>()

    fun addListener(l: Listener) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: Listener) { synchronized(listeners) { listeners.remove(l) } }

    fun canTransition(from: GameState, to: GameState): Boolean {
        if (from == to) return false
        return when (from) {
            GameState.MENU -> to == GameState.CALIBRATING || to == GameState.PLAYING || to == GameState.SETTINGS || to == GameState.LOG_VIEWER
            GameState.CALIBRATING -> to == GameState.PLAYING || to == GameState.MENU || to == GameState.PAUSED
            GameState.PLAYING -> to == GameState.PAUSED || to == GameState.WON || to == GameState.LOST
            GameState.PAUSED -> to == GameState.PLAYING || to == GameState.CALIBRATING || to == GameState.SETTINGS ||
                to == GameState.LOG_VIEWER || to == GameState.MENU
            GameState.WON -> to == GameState.PLAYING || to == GameState.MENU || to == GameState.CALIBRATING
            GameState.LOST -> to == GameState.PLAYING || to == GameState.MENU || to == GameState.CALIBRATING
            GameState.SETTINGS -> to == GameState.PAUSED || to == GameState.MENU
            GameState.LOG_VIEWER -> to == GameState.PAUSED || to == GameState.MENU
        }
    }

    /** Intenta cambiar de estado. Devuelve false (y lo registra) si la transición no es válida. */
    fun transition(to: GameState): Boolean {
        val from = state
        if (!canTransition(from, to)) {
            GameLog.w("State", "Transición inválida $from → $to")
            return false
        }
        state = to
        GameLog.i("State", "$from → $to")
        val copy = synchronized(listeners) { ArrayList(listeners) }
        for (l in copy) l.onStateChanged(from, to)
        return true
    }

    /** Fuerza un estado sin validar (restauración tras crash/destrucción). */
    fun force(to: GameState) {
        val from = state
        state = to
        if (from != to) {
            val copy = synchronized(listeners) { ArrayList(listeners) }
            for (l in copy) l.onStateChanged(from, to)
        }
    }
}
