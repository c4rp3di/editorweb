package com.laberinto3d.juego.game.physics

import com.laberinto3d.juego.game.core.Config
import com.laberinto3d.juego.game.core.Tuning
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Motor de física de paso fijo. Mundo estático de cajas AABB, una bola esférica y
 * una gravedad vectorial que cambia según la inclinación del móvil.
 * Sólo se llama desde el hilo GL.
 */
class PhysicsEngine(
    private val world: World,
    @JvmField val ball: Ball,
    private val start: Vec3
) {
    /** Gravedad actual (la fija GameLoop cada paso a partir del acelerómetro). */
    @JvmField val gravity = Vec3(0f, -Config.GRAVITY, 0f)

    /** Punto de reaparición (inicio o último checkpoint). Centro de la bola. */
    private val spawn = Vec3(start.x, start.y, start.z)

    /** Índice del último checkpoint alcanzado (-1 = ninguno). */
    var checkpointIndex = -1
        private set

    /** Aviso al alcanzar un checkpoint nuevo (índice). */
    var checkpointListener: ((Int) -> Unit)? = null

    var won = false
        private set
    var fell = false
        private set

    /** Notificación de impacto contra pared: (velocidad de impacto, normal x, normal z). */
    var impactListener: ((Float, Float, Float) -> Unit)? = null
    /** Notificación de eventos raros (atascada, velocidad excesiva...) para el log. */
    var warnListener: ((String) -> Unit)? = null

    // ---- Estadísticas para el overlay de depuración ----
    @JvmField var contactsLastStep = 0
    @JvmField var totalContacts = 0L
    @JvmField var maxImpactSeen = 0f
    @JvmField var resetsByStuck = 0
    @JvmField var stepsRun = 0L

    private val contact = Contact()
    private var simTime = 0f
    /** Superficie del suelo sobre el que rodó la bola en el paso anterior (0 normal, 1 hielo, 2 arena). */
    private var surfaceNow = 0
    private var contactCount = 0
    private var stuckCounter = 0
    private var warnedSpeed = false

    init {
        resetBall()
    }

    /** Reaparece en el último checkpoint (o en el inicio). */
    fun resetBall() {
        ball.placeAt(spawn)
        won = false
        fell = false
        stuckCounter = 0
    }

    fun step(dt: Float) {
        val b = ball
        val v = b.velocity
        val p = b.position

        b.restitution = Tuning.restitution
        b.friction = Tuning.wallFriction
        b.linearDamping = Tuning.damping

        b.prevPosition.set(p)
        b.prevRotation.set(b.rotation)

        // 0) plataformas móviles
        simTime += dt
        world.updateMovers(simTime, dt)

        // 1) gravedad
        v.x += gravity.x * dt
        v.y += gravity.y * dt
        v.z += gravity.z * dt

        // 2) amortiguamiento lineal (hielo: casi nada; arena: mucho)
        val dampK = when (surfaceNow) { 1 -> 0.08f; 2 -> 5f; else -> 1f }
        val damp = (1f - b.linearDamping * dampK * dt).coerceAtLeast(0f)
        v.x *= damp; v.y *= damp; v.z *= damp

        // Límite de velocidad (red de seguridad contra el tunneling)
        val sp = v.length()
        if (sp > Config.SPEED_WARN && !warnedSpeed) {
            warnedSpeed = true
            warnListener?.invoke("Velocidad alta: ${"%.1f".format(sp)} m/s")
        }
        if (sp > Config.SPEED_MAX) {
            val k = Config.SPEED_MAX / sp
            v.x *= k; v.y *= k; v.z *= k
        }

        // 3) integración
        p.x += v.x * dt
        p.y += v.y * dt
        p.z += v.z * dt

        // 4) colisiones (cajas, plataformas móviles y triángulos de rampas)
        contactCount = 0
        surfaceNow = 0
        for (iter in 0 until Config.COLLISION_ITERATIONS) {
            for (i in world.boxes.indices) {
                val box = world.boxes[i]
                if (!Collision.sphereAabb(p.x, p.y, p.z, b.radius, box, contact)) continue
                val dyn = box.dynamic
                resolve(iter, dt, if (dyn) box.vel.x else 0f, if (dyn) box.vel.y else 0f, if (dyn) box.vel.z else 0f, dyn, box.dvx, box.dvz)
                if (contact.ny > 0.7f && box.surface != 0) surfaceNow = box.surface
            }
            val tv = world.tris
            val tn = world.triNormals
            var ti = 0
            while (ti < tn.size) {
                if (Collision.sphereTri(p.x, p.y, p.z, b.radius, tv, ti * 3, tn[ti], tn[ti + 1], tn[ti + 2], contact)) {
                    resolve(iter, dt, 0f, 0f, 0f, false, 0f, 0f)
                }
                ti += 3
            }
        }
        val contacts = contactCount
        contactsLastStep = contacts
        totalContacts += contacts
        stepsRun++
        if (abs(v.y) < 0.005f) v.y = 0f

        // Atascada: penetración grave que persiste tras las iteraciones
        var deep = false
        for (i in world.boxes.indices) {
            if (Collision.sphereAabb(p.x, p.y, p.z, b.radius, world.boxes[i], contact) &&
                contact.depth > Config.STUCK_DEPTH
            ) { deep = true; break }
        }
        if (deep) {
            stuckCounter++
            if (stuckCounter >= Config.STUCK_STEPS) {
                resetsByStuck++
                warnListener?.invoke("Bola atascada: se reinicia al punto de inicio")
                resetBall()
                return
            }
        } else stuckCounter = 0

        // 5) rotación visual (rodadura sin deslizamiento): eje = up x v
        val hx = v.x
        val hz = v.z
        val hs = sqrt(hx * hx + hz * hz)
        if (hs > 1e-4f) {
            // up x v = (0,1,0) x (hx,0,hz) = (hz, 0, -hx)
            val ax = hz / hs
            val az = -hx / hs
            val angle = hs * dt / b.radius
            q.setAxisAngle(ax, 0f, az, angle)
            b.rotation.setMul(q, b.rotation).normalize()
        }

        // 6) victoria
        val gdx = p.x - world.goalPosition.x
        val gdz = p.z - world.goalPosition.z
        val gdy = p.y - b.radius - world.goalPosition.y
        if (gdx * gdx + gdz * gdz < world.goalRadius * world.goalRadius &&
            abs(gdy) < Config.GOAL_HEIGHT_TOLERANCE
        ) won = true

        // 6b) checkpoints
        val cps = world.checkpoints
        for (i in checkpointIndex + 1 until cps.size) {
            val c = cps[i]
            val dx = p.x - c.x
            val dz = p.z - c.z
            if (dx * dx + dz * dz < Config.CHECKPOINT_RADIUS * Config.CHECKPOINT_RADIUS &&
                abs(p.y - b.radius - c.y) < Config.CHECKPOINT_HEIGHT_TOL
            ) {
                checkpointIndex = i
                spawn.set(c.x, c.y + b.radius, c.z)
                checkpointListener?.invoke(i)
            }
        }

        // 7) caída / fuera del tablero
        val m = Config.OUT_OF_BOUNDS_MARGIN
        if (p.y < Config.FALL_Y || p.x < world.minX - m || p.x > world.maxX + m ||
            p.z < world.minZ - m || p.z > world.maxZ + m
        ) fell = true
    }

    private val q = Quat()

    /**
     * Respuesta al contacto guardado en [contact]: saca la bola, anula/rebota la velocidad normal
     * (relativa a la superficie, que puede moverse a [bvx],[bvy],[bvz]) y aplica rozamiento de pared.
     */
    private fun resolve(iter: Int, dt: Float, bvx: Float, bvy: Float, bvz: Float, dyn: Boolean, dvx: Float, dvz: Float) {
        val b = ball
        val v = b.velocity
        val p = b.position
        contactCount++
        val nx = contact.nx
        val ny = contact.ny
        val nz = contact.nz
        p.x += nx * contact.depth
        p.y += ny * contact.depth
        p.z += nz * contact.depth
        val vn = (v.x - bvx) * nx + (v.y - bvy) * ny + (v.z - bvz) * nz
        if (vn < 0f) {
            val impact = -vn
            val e = if (impact > Config.REST_THRESHOLD) b.restitution else 0f
            val jn = (1f + e) * impact
            v.x += nx * jn; v.y += ny * jn; v.z += nz * jn
            val floorLike = ny > 0.7f
            if (!floorLike) {
                val rvx = v.x - bvx; val rvy = v.y - bvy; val rvz = v.z - bvz
                val vn2 = rvx * nx + rvy * ny + rvz * nz
                val tx = rvx - nx * vn2
                val ty = rvy - ny * vn2
                val tz = rvz - nz * vn2
                val tl = sqrt(tx * tx + ty * ty + tz * tz)
                if (tl > 1e-6f) {
                    val dv = minOf(tl, b.friction * jn)
                    val k = dv / tl
                    v.x -= tx * k; v.y -= ty * k; v.z -= tz * k
                }
                if (impact > Config.IMPACT_MIN_SPEED && iter == 0) {
                    if (impact > maxImpactSeen) maxImpactSeen = impact
                    impactListener?.invoke(impact, nx, nz)
                }
            }
        }
        // una plataforma móvil arrastra a la bola que va encima: la bola hereda la ACELERACIÓN horizontal
        // de la plataforma (agarre perfecto) y su movimiento relativo se amortigua suavemente, de modo que
        // se puede seguir rodando sobre ella con normalidad.
        if (dyn && ny > 0.7f && iter == 0) {
            v.x += dvx
            v.z += dvz
            val k = minOf(1f, 1.5f * dt)
            v.x += (bvx - v.x) * k
            v.z += (bvz - v.z) * k
        }
    }
}
