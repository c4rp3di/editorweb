package com.laberinto3d.juego.game.render

/**
 * Código fuente GLSL ES 3.00 de todos los programas. Shaders simples, precisión highp
 * en fragment (obligatoria en ES 3.0), sin operaciones exóticas.
 *
 * Locations de atributos:
 *   world : 0 pos, 1 normal, 2..5 mat4 de instancia, 6 color de instancia
 *   ball  : 0 pos, 1 normal, 2 uv
 *   quad  : 0 pos (xy en [-1,1])
 */
object Shaders {

    const val WORLD_VERT = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in mat4 aModel;
layout(location = 6) in vec4 aColor;
uniform mat4 uViewProj;
out vec3 vNormal;
out vec4 vColor;
out vec3 vWorld;
void main() {
    vec4 w = aModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = aNormal;
    vColor = aColor;
    gl_Position = uViewProj * w;
}
"""

    /** alpha del color de instancia = material: 0.5 suelo con rejilla, 1.0 pared. */
    const val WORLD_FRAG = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec4 vColor;
in vec3 vWorld;
uniform vec3 uLightDir;
uniform float uAmbient;
layout(location = 0) out vec4 oColor;
void main() {
    vec3 n = normalize(vNormal);
    float ndl = max(dot(n, uLightDir), 0.0);
    float shade = 1.0;
    if (n.y > 0.5) {
        if (vColor.a < 0.75) {
            vec2 g = abs(fract(vWorld.xz / 0.5 + 0.5) - 0.5);
            float line = 1.0 - smoothstep(0.0, 0.02, min(g.x, g.y));
            shade = 1.0 - 0.12 * line;
        }
    } else {
        // caras laterales: más oscuras cuanto más abajo (da sensación de profundidad)
        shade = mix(0.45, 1.0, smoothstep(-0.4, 0.3, vWorld.y));
    }
    vec3 lit = vColor.rgb * (uAmbient + ndl * 0.85) * shade;
    oColor = vec4(lit, 1.0);
}
"""

    /** Malla de triángulos del mundo (rampas): pos, normal, color rgba por vértice. */
    const val MESH_VERT = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 6) in vec4 aColor;
uniform mat4 uViewProj;
out vec3 vNormal;
out vec4 vColor;
out vec3 vWorld;
void main() {
    vWorld = aPos;
    vNormal = aNormal;
    vColor = aColor;
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
"""

    const val BALL_VERT = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
uniform mat4 uViewProj;
uniform mat4 uModel;
out vec3 vWorld;
out vec3 vNormal;
out vec2 vUv;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = mat3(uModel) * aNormal;
    vUv = aUv;
    gl_Position = uViewProj * w;
}
"""

    const val BALL_FRAG = """#version 300 es
precision highp float;
in vec3 vWorld;
in vec3 vNormal;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec3 uLightDir;
uniform vec3 uCamPos;
uniform float uAmbient;
layout(location = 0) out vec4 oColor;
void main() {
    vec3 tex = texture(uTex, vUv).rgb;
    vec3 n = normalize(vNormal);
    vec3 v = normalize(uCamPos - vWorld);
    vec3 h = normalize(uLightDir + v);
    float diff = max(dot(n, uLightDir), 0.0);
    float spec = pow(max(dot(n, h), 0.0), 40.0);
    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.12;
    vec3 col = tex * (uAmbient + diff * 0.85) + vec3(spec * 0.45) + vec3(rim);
    oColor = vec4(col, 1.0);
}
"""

    /** Quad horizontal en el suelo: centro (xyz) y radio (w) por uniform. */
    const val QUAD_VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat4 uViewProj;
uniform vec4 uCenterRadius;
out vec2 vUv;
void main() {
    vUv = aPos;
    vec3 w = vec3(uCenterRadius.x + aPos.x * uCenterRadius.w,
                  uCenterRadius.y,
                  uCenterRadius.z + aPos.y * uCenterRadius.w);
    gl_Position = uViewProj * vec4(w, 1.0);
}
"""

    const val SHADOW_FRAG = """#version 300 es
precision highp float;
in vec2 vUv;
uniform float uAlpha;
layout(location = 0) out vec4 oColor;
void main() {
    float d = length(vUv);
    float a = (1.0 - smoothstep(0.30, 1.0, d)) * uAlpha;
    oColor = vec4(0.0, 0.0, 0.0, a);
}
"""

    const val GOAL_FRAG = """#version 300 es
precision highp float;
in vec2 vUv;
uniform float uTime;
uniform vec3 uColA;
uniform vec3 uColB;
uniform float uPulse;
layout(location = 0) out vec4 oColor;
void main() {
    float d = length(vUv);
    float ring = smoothstep(0.80, 0.88, d) - smoothstep(0.95, 1.0, d);
    float fill = (1.0 - smoothstep(0.0, 0.85, d)) * 0.38;
    float pulse = 1.0 - uPulse + uPulse * (0.78 + 0.22 * sin(uTime * 3.0));
    float a = (ring * 0.95 + fill) * pulse;
    if (a < 0.01) discard;
    vec3 c = mix(uColA, uColB, ring);
    oColor = vec4(c, a);
}
"""
}
