package com.perplexus.game.math

/** Transformación rígida con escala uniforme: traslación + rotación + escala. */
class Transform(
    @JvmField val position: Vec3 = Vec3(),
    @JvmField val rotation: Quat = Quat(),
    @JvmField var scale: Float = 1f
) {
    fun toMat4(out: FloatArray) {
        Mat4.trs(out, rotation, position.x, position.y, position.z, scale)
    }

    fun transformPoint(p: Vec3, out: Vec3): Vec3 {
        rotation.rotateInto(p, out)
        out.scale(scale)
        out.add(position)
        return out
    }

    fun inverseTransformPoint(p: Vec3, out: Vec3): Vec3 {
        out.set(p).sub(position)
        if (scale != 0f) out.scale(1f / scale)
        rotation.inverseRotateInto(out, out)
        return out
    }

    fun set(o: Transform): Transform {
        position.set(o.position); rotation.set(o.rotation); scale = o.scale
        return this
    }

    fun copy(): Transform = Transform(position.copy(), rotation.copy(), scale)
}
