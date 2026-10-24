package com.perplexus.game.render

import com.perplexus.game.math.Mat4
import com.perplexus.game.math.MathUtils
import com.perplexus.game.math.Vec3
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Cámara en perspectiva que observa la esfera desde fuera. Órbita (yaw/pitch) y zoom se
 * modifican desde el hilo de UI (campos volátiles) y se leen en el hilo GL.
 */
class Camera {
    @Volatile var yaw: Float = 0f
    @Volatile var pitch: Float = MathUtils.toRad(RenderConstants.CAMERA_DEFAULT_PITCH_DEG)
    @Volatile var zoom: Float = 1f
    @Volatile var shellRadius: Float = 1f

    @JvmField val view = FloatArray(16)
    @JvmField val proj = FloatArray(16)
    @JvmField val viewProj = FloatArray(16)
    @JvmField val eye = Vec3()
    @JvmField var fovY: Float = 0.6f
    @JvmField var distance: Float = 3.2f

    fun reset() {
        yaw = 0f
        pitch = MathUtils.toRad(RenderConstants.CAMERA_DEFAULT_PITCH_DEG)
        zoom = 1f
    }

    fun orbit(dYaw: Float, dPitch: Float) {
        var y = yaw + dYaw
        if (y > MathUtils.PI_F) y -= MathUtils.TWO_PI_F else if (y < -MathUtils.PI_F) y += MathUtils.TWO_PI_F
        yaw = y
        pitch = MathUtils.clamp(pitch + dPitch, MathUtils.toRad(-80f), MathUtils.toRad(80f))
    }

    fun zoomBy(factor: Float) {
        zoom = MathUtils.clamp(zoom * factor, RenderConstants.CAMERA_MIN_ZOOM, RenderConstants.CAMERA_MAX_ZOOM)
    }

    /** Recalcula matrices. La distancia se limita para no entrar nunca en la esfera (RF-14). */
    fun update(aspect: Float) {
        val r = shellRadius
        val base = RenderConstants.CAMERA_BASE_DISTANCE * r
        distance = max(base * zoom, RenderConstants.CAMERA_MIN_DISTANCE_R * r)
        // FOV: encaja la esfera (a distancia base) con 4 % de margen en la dimensión más estrecha
        val theta = asin(MathUtils.clamp(r / base, 0.05f, 0.95f))
        val tanHalf = tan(theta) * 1.04f * max(1f, 1f / aspect)
        fovY = max(2f * atan(tanHalf), MathUtils.toRad(RenderConstants.FOV_MIN_DEG))
        val cp = cos(pitch); val sp = sin(pitch)
        eye.set(distance * sin(yaw) * cp, distance * sp, distance * cos(yaw) * cp)
        Mat4.perspective(proj, fovY, aspect, RenderConstants.CAMERA_NEAR, RenderConstants.CAMERA_FAR)
        Mat4.lookAt(view, eye.x, eye.y, eye.z, 0f, 0f, 0f, 0f, 1f, 0f)
        Mat4.multiply(viewProj, proj, view)
    }
}
