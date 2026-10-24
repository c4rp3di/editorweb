package com.perplexus.game.input

/** Persistencia del sesgo del giroscopio (implementada sobre Prefs en la app; en tests, en memoria). */
interface BiasStorage {
    fun loadBias(): FloatArray
    fun saveBias(b: FloatArray)
}

class MemoryBiasStorage : BiasStorage {
    private var b = floatArrayOf(0f, 0f, 0f)
    override fun loadBias(): FloatArray = b.copyOf()
    override fun saveBias(b: FloatArray) { this.b = b.copyOf() }
}

class CalibrationStore(private val storage: BiasStorage) {
    fun load(): FloatArray = storage.loadBias()
    fun save(x: Float, y: Float, z: Float) = storage.saveBias(floatArrayOf(x, y, z))
}
