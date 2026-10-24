package com.perplexus.game.physics

import com.perplexus.game.math.Vec3

/** Contacto bola-geometría. La normal apunta de la superficie hacia la bola. */
class Contact {
    @JvmField val normal = Vec3()
    @JvmField val point = Vec3()
    @JvmField var penetration: Float = 0f
    @JvmField var restitution: Float = 0f
    @JvmField var staticFriction: Float = 0f
    @JvmField var dynamicFriction: Float = 0f
    @JvmField var rolling: Float = 0f
    @JvmField var lethal: Boolean = false
    @JvmField var shapeId: Int = -1

    // Datos del solver
    @JvmField val tangent1 = Vec3()
    @JvmField val tangent2 = Vec3()
    @JvmField var bias: Float = 0f
    @JvmField var normalImpulse: Float = 0f
    @JvmField var tangentImpulse1: Float = 0f
    @JvmField var tangentImpulse2: Float = 0f
    @JvmField var preSolveNormalSpeed: Float = 0f

    fun reset() {
        normal.setZero(); point.setZero()
        penetration = 0f; restitution = 0f; staticFriction = 0f; dynamicFriction = 0f; rolling = 0f
        lethal = false; shapeId = -1
        bias = 0f; normalImpulse = 0f; tangentImpulse1 = 0f; tangentImpulse2 = 0f; preSolveNormalSpeed = 0f
    }
}

/** Buffer pre-asignado de contactos (sin allocation en el bucle). */
class ContactBuffer(capacity: Int = PhysicsConstants.MAX_CONTACTS) {
    @JvmField val items: Array<Contact> = Array(capacity) { Contact() }
    @JvmField var count: Int = 0

    fun clear() { count = 0 }

    fun next(): Contact? {
        if (count >= items.size) return null
        val c = items[count++]
        c.reset()
        return c
    }

    operator fun get(i: Int): Contact = items[i]
}
