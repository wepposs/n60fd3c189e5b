#version 330
#extension GL_ARB_separate_shader_objects : require

#include <vesence:sky_common.glsl>

layout(location = 0) out vec4 OutColor;

// CRYSTAL — морозное небо из ледяных кристаллов: полный набор гало
// (22° и 46° кольца, паргелии, околозенитная дуга, солнечный столб),
// перламутровые облака с переливом и сверкающая алмазная пыль.

// Цветная полоса гало: красный внутри, синий снаружи.
vec3 haloBand(float ang, float r, float w) {
    float s = (ang - r) / w;
    float band = smoothstep(-0.15, 0.0, s) * (1.0 - smoothstep(0.2, 1.6, s));
    return mix(vec3(1.0, 0.40, 0.22), mix(vec3(1.0, 0.95, 0.85), vec3(0.45, 0.65, 1.0), smoothstep(0.4, 1.2, s)), smoothstep(-0.1, 0.4, s)) * band;
}

void main() {
    vec3 d = worldRay(gl_FragCoord.xy);
    vec3 sunDir = worldDir(40.0, 20.0);
    vec3 T = sunTransmit(sunDir, 0.15);

    vec3 col = atmosphere(d, sunDir, 22.0, 1.05, 0.15, 1.2) * vec3(0.92, 0.98, 1.06);
    col += sunDisc(d, sunDir, 0.017, T);

    float ang = degrees(acos(clamp(dot(d, sunDir), -1.0, 1.0)));
    float up = smoothstep(-0.02, 0.06, d.y);
    vec3 halo = haloBand(ang, 22.0, 1.4) * 0.22 + haloBand(ang, 46.0, 2.2) * 0.07;
    // Паргелии — ложные солнца по бокам на высоте солнца.
    for (int i = 0; i < 2; i++) {
        float sgn = i == 0 ? 1.0 : -1.0;
        vec3 dog = worldDir(40.0 + sgn * 23.5, 20.0);
        vec2 uv = tangentUV(d, dog);
        float a = length(uv * vec2(1.0, 1.8));
        float tail = bell(uv.y, 0.006) * smoothstep(0.0, 0.01, uv.x * sgn * -1.0) * exp(-abs(uv.x) * 14.0);
        halo += mix(vec3(1.0, 0.45, 0.25), vec3(0.7, 0.85, 1.0), smoothstep(0.0, 0.04, a)) * (bell(a, 0.012) * 1.1 + bell(a, 0.04) * 0.2) + vec3(0.9) * tail * 0.25;
    }
    // Околозенитная дуга — самая яркая радуга неба.
    vec3 zen = vec3(0.0, 1.0, 0.0);
    float za = degrees(acos(clamp(dot(d, zen), -1.0, 1.0)));
    float cza = (za - (90.0 - 20.0 - 46.0) + 1.0) / 2.0;
    float czaMask = smoothstep(0.0, 0.25, dot(normalize(vec3(d.x, 0.0, d.z)), normalize(vec3(sunDir.x, 0.0, sunDir.z))) - 0.35);
    halo += spectral(0.85 - clamp(cza, 0.0, 1.0) * 0.85) * smoothstep(0.0, 0.15, cza) * (1.0 - smoothstep(0.85, 1.0, cza)) * czaMask * 0.35;
    // Солнечный столб.
    vec2 su = tangentUV(d, sunDir);
    halo += T * bell(su.x, 0.006) * exp(-abs(su.y) * 5.0) * 0.6;
    col += halo * T * up;

    // Перламутровые облака: тонкие линзы с интерференционным переливом.
    vec3 pp = d * cloudShell(d, 6.0);
    vec2 q = pp.xz * 0.09 + vec2(Time * 0.003, 0.0);
    float lens = fbm(vec3(q * vec2(1.0, 2.6), 4.0), 5);
    float sheet = smoothstep(0.52, 0.72, lens) * smoothstep(0.02, 0.2, d.y);
    float thick = fbm(vec3(q * 3.0, 9.0), 3) + lens * 1.6 + ang * 0.012;
    vec3 iri = spectral(thick * 1.3) * 0.9 + 0.35;
    col = mix(col, iri * (0.55 + 0.6 * pow(max(dot(d, sunDir), 0.0), 3.0)), sheet * 0.55);

    // Лёгкий высокий ледяной ярус.
    col = cirrusVeil(col, d, sunDir, vec3(1.0, 0.98, 0.96) * 0.9, vec3(0.55, 0.66, 0.85), 0.35, 13.0);

    // Алмазная пыль: мелкие искры, сверкают ближе к солнцу.
    float sparkle = 0.6 + 0.8 * pow(max(dot(d, sunDir), 0.0), 4.0);
    float dust = particleShell(d, 6.0, 0.7, 0.035, vec3(0.08, -0.10, 0.04), 3.0, 0.0)
               + particleShell(d, 11.0, 1.1, 0.04, vec3(0.08, -0.08, 0.04), 9.0, 0.0) * 0.8;
    col += vec3(0.9, 0.96, 1.0) * dust * sparkle * 1.4 * up;

    col = grade(col, 1.08, 0.0);
    OutColor = skyOutput(col, d);
}
