package com.perplexus.game.input

/** Muestra de sensor con timestamp (ns desde boot, reloj común de los sensores). */
class SensorSample {
    @JvmField var t: Long = 0L
    @JvmField var x: Float = 0f
    @JvmField var y: Float = 0f
    @JvmField var z: Float = 0f
}

/**
 * Buffer circular lock-free de un productor (hilo de sensores) y un consumidor (hilo GL).
 * El productor solo escribe [head]; el consumidor solo escribe [tail]. Si se llena, se descartan
 * las muestras nuevas y se cuenta en [dropped].
 */
class SampleRing(capacityPow2: Int = 1024) {
    private val cap: Int
    private val mask: Int
    private val ts: LongArray
    private val xs: FloatArray
    private val ys: FloatArray
    private val zs: FloatArray

    @Volatile private var head: Int = 0
    @Volatile private var tail: Int = 0
    @Volatile var dropped: Int = 0
        private set
    @Volatile var pushed: Long = 0L
        private set

    init {
        var c = 16
        while (c < capacityPow2) c = c shl 1
        cap = c
        mask = c - 1
        ts = LongArray(c); xs = FloatArray(c); ys = FloatArray(c); zs = FloatArray(c)
    }

    /** Solo desde el hilo productor. */
    fun push(t: Long, x: Float, y: Float, z: Float): Boolean {
        val h = head
        if (h - tail >= cap) { dropped++; return false }
        val i = h and mask
        ts[i] = t; xs[i] = x; ys[i] = y; zs[i] = z
        head = h + 1
        pushed++
        return true
    }

    /** Solo desde el hilo consumidor. */
    fun available(): Int = head - tail

    /** Timestamp de la siguiente muestra sin consumirla (requiere available() > 0). */
    fun peekTime(): Long = ts[tail and mask]

    /** Consume la siguiente muestra. */
    fun pop(out: SensorSample): Boolean {
        val tl = tail
        if (tl >= head) return false
        val i = tl and mask
        out.t = ts[i]; out.x = xs[i]; out.y = ys[i]; out.z = zs[i]
        tail = tl + 1
        return true
    }

    /** Descarta todo lo pendiente (consumidor). */
    fun clear() { tail = head }
}
