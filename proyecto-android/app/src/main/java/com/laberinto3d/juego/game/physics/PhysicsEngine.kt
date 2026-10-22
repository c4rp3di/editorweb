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
    private var stuckCounter = 0
    private var warnedSpeed = false

    init {
        resetBall()
    }

    fun resetBall() {
        ball.placeAt(start)
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

        // 1) gravedad
        v.x += gravity.x * dt
        v.y += gravity.y * dt
        v.z += gravity.z * dt

        // 2) amortiguamiento lineal
        val damp = (1f - b.linearDamping * dt).coerceAtLeast(0f)
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

        // 4) colisiones
        var contacts = 0
        for (iter in 0 until Config.COLLISION_ITERATIONS) {
            for (i in world.boxes.indices) {
                val box = world.boxes[i]
                if (!Collision.sphereAabb(p.x, p.y, p.z, b.radius, box, contact)) continue
                contacts++
                val nx = contact.nx
                val ny = contact.ny
                val nz = contact.nz
                // push-out
                p.x += nx * contact.depth
                p.y += ny * contact.depth
                p.z += nz * contact.depth
                // respuesta de velocidad
                val vn = v.x * nx + v.y * ny + v.z * nz
                if (vn < 0f) {
                    val impact = -vn
                    val e = if (impact > Config.REST_THRESHOLD) b.restitution else 0f
                    val jn = (1f + e) * impact          // variación de velocidad normal
                    v.x += nx * jn; v.y += ny * jn; v.z += nz * jn
                    val floorLike = ny > 0.7f
                    if (!floorLike) {
                        // fricción de Coulomb: limitada por mu * impulso normal
                        val vn2 = v.x * nx + v.y * ny + v.z * nz
                        val tx = v.x - nx * vn2
                        val ty = v.y - ny * vn2
                        val tz = v.z - nz * vn2
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
            }
        }
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

        // 7) caída / fuera del tablero
        val m = Config.OUT_OF_BOUNDS_MARGIN
        if (p.y < Config.FALL_Y || p.x < world.minX - m || p.x > world.maxX + m ||
            p.z < world.minZ - m || p.z > world.maxZ + m
        ) fell = true
    }

    private val q = Quat()
}
