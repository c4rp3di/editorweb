package com.perplexus.game.diag

/** Instantánea de métricas escrita por el hilo GL y leída por el overlay de depuración (UI). */
class DebugData {
    @Volatile var fps = 0f
    @Volatile var physicsMs = 0f
    @Volatile var sceneMs = 0f
    @Volatile var postMs = 0f
    @Volatile var frameMs = 0f
    @Volatile var px = 0f; @Volatile var py = 0f; @Volatile var pz = 0f
    @Volatile var vx = 0f; @Volatile var vy = 0f; @Volatile var vz = 0f
    @Volatile var wx = 0f; @Volatile var wy = 0f; @Volatile var wz = 0f
    @Volatile var gx = 0f; @Volatile var gy = 0f; @Volatile var gz = 0f
    @Volatile var qw = 1f; @Volatile var qx = 0f; @Volatile var qy = 0f; @Volatile var qz = 0f
    @Volatile var contacts = 0
    @Volatile var solverIterations = 0
    @Volatile var steps = 0
    @Volatile var shapes = 0
    @Volatile var bvhNodes = 0
    @Volatile var gyroHz = 0f
    @Volatile var accelHz = 0f
    @Volatile var gyroSuspicious = false
    @Volatile var latencyMs = 0f
    @Volatile var particles = 0
    @Volatile var state = ""
    @Volatile var emergencyResets = 0

    fun toText(): String {
        val sb = StringBuilder()
        sb.append("FPS %.1f  frame %.2f ms  [%s]\n".format(fps, frameMs, state))
        sb.append("física %.2f ms (%d pasos)  escena %.2f ms  post %.2f ms\n".format(physicsMs, steps, sceneMs, postMs))
        sb.append("pos   (%.3f, %.3f, %.3f)\n".format(px, py, pz))
        sb.append("vel   (%.2f, %.2f, %.2f)  |v|=%.2f\n".format(vx, vy, vz, kotlin.math.sqrt(vx * vx + vy * vy + vz * vz)))
        sb.append("ω     (%.1f, %.1f, %.1f)\n".format(wx, wy, wz))
        sb.append("g     (%.2f, %.2f, %.2f)\n".format(gx, gy, gz))
        sb.append("q     (%.3f, %.3f, %.3f, %.3f)\n".format(qw, qx, qy, qz))
        sb.append("contactos %d  iter. solver %d  formas %d  nodos BVH %d\n".format(contacts, solverIterations, shapes, bvhNodes))
        sb.append("sensores: giro %.0f Hz  acel %.0f Hz  lat %.1f ms%s\n".format(gyroHz, accelHz, latencyMs, if (gyroSuspicious) "  ¡GIRO SOSPECHOSO!" else ""))
        sb.append("partículas %d  resets %d".format(particles, emergencyResets))
        return sb.toString()
    }
}
