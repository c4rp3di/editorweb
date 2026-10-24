package com.perplexus.game.render

import android.opengl.GLES30
import com.perplexus.game.diag.GameLog

/** Programa GLSL: compilación, enlace y uniforms (con caché de ubicaciones). */
class Shader(private val name: String, vertexSrc: String, fragmentSrc: String) {
    var program: Int = 0
        private set
    private val locations = HashMap<String, Int>()
    private val tmp = IntArray(1)

    val valid: Boolean get() = program != 0

    init { build(vertexSrc, fragmentSrc) }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, tmp, 0)
        if (tmp[0] == 0) {
            GameLog.e("Shader", "[$name] error de compilación (${if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"}): ${GLES30.glGetShaderInfoLog(s)}")
            GLES30.glDeleteShader(s)
            return 0
        }
        return s
    }

    private fun build(vs: String, fs: String) {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, tmp, 0)
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        if (tmp[0] == 0) {
            GameLog.e("Shader", "[$name] error de enlace: ${GLES30.glGetProgramInfoLog(p)}")
            GLES30.glDeleteProgram(p)
            return
        }
        program = p
        GameLog.i("Shader", "[$name] compilado correctamente")
    }

    fun use() { GLES30.glUseProgram(program) }

    fun loc(uniform: String): Int = locations.getOrPut(uniform) { GLES30.glGetUniformLocation(program, uniform) }

    fun setMat4(u: String, m: FloatArray) { GLES30.glUniformMatrix4fv(loc(u), 1, false, m, 0) }
    fun setMat3(u: String, m: FloatArray) { GLES30.glUniformMatrix3fv(loc(u), 1, false, m, 0) }
    fun setVec2(u: String, x: Float, y: Float) { GLES30.glUniform2f(loc(u), x, y) }
    fun setVec3(u: String, x: Float, y: Float, z: Float) { GLES30.glUniform3f(loc(u), x, y, z) }
    fun setVec4(u: String, x: Float, y: Float, z: Float, w: Float) { GLES30.glUniform4f(loc(u), x, y, z, w) }
    fun setFloat(u: String, v: Float) { GLES30.glUniform1f(loc(u), v) }
    fun setInt(u: String, v: Int) { GLES30.glUniform1i(loc(u), v) }

    fun release() {
        if (program != 0) { GLES30.glDeleteProgram(program); program = 0 }
        locations.clear()
    }
}
