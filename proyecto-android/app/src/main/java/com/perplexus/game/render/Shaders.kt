package com.perplexus.game.render

/** Código fuente GLSL ES 3.00 de todos los programas. */
object Shaders {
    const val WORLD_VERT = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aColor;
uniform mat4 uModel;
uniform mat4 uViewProj;
uniform mat3 uNormalMat;
out vec3 vWorld;
out vec3 vNormal;
out vec2 vUv;
out vec4 vColor;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = normalize(uNormalMat * aNormal);
    vUv = aUv;
    vColor = aColor;
    gl_Position = uViewProj * w;
}
"""

    const val WORLD_FRAG = """#version 300 es
precision highp float;
precision highp sampler2DShadow;
in vec3 vWorld;
in vec3 vNormal;
in vec2 vUv;
in vec4 vColor;
uniform vec3 uCamPos;
uniform vec3 uLightDir;
uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform vec3 uFillDir;
uniform vec3 uFillColor;
uniform vec4 uTint;
uniform float uSpec;
uniform float uShin;
uniform float uAlpha;
uniform vec3 uEmissive;
uniform float uRim;
uniform int uUseTex;
uniform sampler2D uTex;
uniform int uUseShadow;
uniform sampler2DShadow uShadow;
uniform mat4 uLightVP;
uniform float uShadowBias;
uniform float uShadowTexel;
out vec4 outColor;

float shadowFactor(vec3 wp, vec3 n) {
    vec4 lp = uLightVP * vec4(wp + n * 0.012, 1.0);
    vec3 c = lp.xyz / lp.w * 0.5 + 0.5;
    if (c.x < 0.0 || c.x > 1.0 || c.y < 0.0 || c.y > 1.0 || c.z > 1.0) return 1.0;
    float s = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            s += texture(uShadow, vec3(c.xy + vec2(float(x), float(y)) * uShadowTexel, c.z - uShadowBias));
        }
    }
    return s / 9.0;
}

void main() {
    vec3 n = normalize(vNormal);
    vec3 v = normalize(uCamPos - vWorld);
    if (dot(n, v) < 0.0) n = -n;
    vec4 base = vColor * uTint;
    if (uUseTex == 1) base.rgb *= texture(uTex, vUv).rgb;
    float sh = (uUseShadow == 1) ? shadowFactor(vWorld, n) : 1.0;
    vec3 L = -normalize(uLightDir);
    float diff = max(dot(n, L), 0.0) * sh;
    vec3 h = normalize(L + v);
    float spec = pow(max(dot(n, h), 0.0), uShin) * uSpec * sh;
    float fill = max(dot(n, -normalize(uFillDir)), 0.0);
    vec3 col = base.rgb * (uAmbient + uLightColor * diff + uFillColor * fill) + uLightColor * spec + uEmissive;
    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * uRim;
    col += vec3(0.45, 0.65, 1.0) * rim;
    outColor = vec4(col, clamp(base.a * uAlpha + rim * 0.35 + spec * 0.4, 0.0, 1.0));
}
"""

    const val SHELL_FRAG = """#version 300 es
precision highp float;
in vec3 vWorld;
in vec3 vNormal;
in vec2 vUv;
in vec4 vColor;
uniform vec3 uCamPos;
uniform vec3 uLightDir;
uniform float uAlpha;
out vec4 outColor;
void main() {
    vec3 n = normalize(vNormal);
    vec3 v = normalize(uCamPos - vWorld);
    float f = pow(1.0 - abs(dot(n, v)), 2.5);
    vec3 col = mix(vec3(0.30, 0.45, 0.85), vec3(0.80, 0.92, 1.0), f);
    // Retícula de meridianos/paralelos para percibir la rotación
    vec2 g = vec2(vUv.x * 24.0, vUv.y * 12.0);
    vec2 w = max(fwidth(g), vec2(0.0001));
    vec2 d = abs(fract(g - 0.5) - 0.5) / w;
    float line = 1.0 - min(min(d.x, d.y), 1.0);
    float spec = pow(max(dot(reflect(normalize(uLightDir), n), v), 0.0), 48.0);
    float a = 0.035 + 0.45 * f + line * 0.10 + spec * 0.5;
    outColor = vec4(col + spec, clamp(a * uAlpha, 0.0, 0.9));
}
"""

    const val SHADOW_VERT = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uModel;
uniform mat4 uLightVP;
void main() { gl_Position = uLightVP * uModel * vec4(aPos, 1.0); }
"""
    const val SHADOW_FRAG = """#version 300 es
precision mediump float;
void main() { }
"""

    const val PARTICLE_VERT = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in float aSize;
layout(location = 2) in vec4 aColor;
uniform mat4 uModel;
uniform mat4 uViewProj;
uniform float uScale;
out vec4 vColor;
void main() {
    gl_Position = uViewProj * uModel * vec4(aPos, 1.0);
    gl_PointSize = max(1.0, aSize * uScale / max(gl_Position.w, 0.1));
    vColor = aColor;
}
"""
    const val PARTICLE_FRAG = """#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 outColor;
void main() {
    vec2 c = gl_PointCoord * 2.0 - 1.0;
    float d = dot(c, c);
    if (d > 1.0) discard;
    float a = 1.0 - d;
    outColor = vec4(vColor.rgb, vColor.a * a * a);
}
"""

    const val POST_VERT = """#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""
    const val BRIGHT_FRAG = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uScene;
out vec4 outColor;
void main() {
    vec3 c = texture(uScene, vUv).rgb;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    outColor = vec4(c * smoothstep(0.62, 1.0, l), 1.0);
}
"""
    const val BLUR_FRAG = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uDir;
out vec4 outColor;
void main() {
    vec3 s = texture(uTex, vUv).rgb * 0.2270270270;
    s += texture(uTex, vUv + uDir * 1.3846153846).rgb * 0.3162162162;
    s += texture(uTex, vUv - uDir * 1.3846153846).rgb * 0.3162162162;
    s += texture(uTex, vUv + uDir * 3.2307692308).rgb * 0.0702702703;
    s += texture(uTex, vUv - uDir * 3.2307692308).rgb * 0.0702702703;
    outColor = vec4(s, 1.0);
}
"""
    const val FINAL_FRAG = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform vec2 uTexel;
uniform float uBloomStrength;
uniform float uVignette;
uniform int uFxaa;
out vec4 outColor;
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
void main() {
    vec3 c = texture(uScene, vUv).rgb;
    if (uFxaa == 1) {
        vec3 n = texture(uScene, vUv + vec2(0.0, uTexel.y)).rgb;
        vec3 s = texture(uScene, vUv - vec2(0.0, uTexel.y)).rgb;
        vec3 e = texture(uScene, vUv + vec2(uTexel.x, 0.0)).rgb;
        vec3 w = texture(uScene, vUv - vec2(uTexel.x, 0.0)).rgb;
        float lm = luma(c);
        float lmin = min(lm, min(min(luma(n), luma(s)), min(luma(e), luma(w))));
        float lmax = max(lm, max(max(luma(n), luma(s)), max(luma(e), luma(w))));
        float edge = lmax - lmin;
        if (edge > max(0.0312, lmax * 0.125)) {
            vec3 avg = (n + s + e + w) * 0.25;
            c = mix(c, avg, 0.5);
        }
    }
    c += texture(uBloom, vUv).rgb * uBloomStrength;
    vec2 d = vUv - 0.5;
    float vig = 1.0 - smoothstep(0.35, 0.85, length(d)) * uVignette;
    outColor = vec4(c * vig, 1.0);
}
"""
}
