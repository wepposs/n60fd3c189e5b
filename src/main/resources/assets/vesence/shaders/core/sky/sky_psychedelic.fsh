#version 330
#extension GL_ARB_separate_shader_objects : require

#include <vesence:sky_common.glsl>

layout(location = 0) out vec4 OutColor;

// PSYCHEDELIC — сумерки после извержения вулкана: стратосферный аэрозоль
// окрашивает небо в густой фиолетовый и малиновый, перистые горят
// красным, облака подсвечены розовым снизу.
void main() {
    vec3 d = worldRay(gl_FragCoord.xy);
    vec3 sunDir = worldDir(30.0, -2.0);
    vec3 T = sunTransmit(worldDir(30.0, 1.5), 1.6);

    vec3 col = atmosphere(d, sunDir, 26.0, 1.0, 2.4, 2.0);
    float h = max(d.y, 0.0);
    // Аэрозольный слой: пурпурное свечение на высоте 20-30 градусов.
    float mu = max(dot(normalize(vec3(d.x, 0.0, d.z)), normalize(vec3(sunDir.x, 0.0, sunDir.z))), 0.0);
    col += vec3(0.55, 0.18, 0.60) * bell(h - 0.35, 0.22) * (0.5 + 0.5 * mu) * 0.15;
    col += stars(d, 0.6) * smoothstep(0.05, 0.01, luma(col));

    vec3 haze = SkyPass > 1.5 ? vec3(0.0) : atmosphere(vec3(d.x, 0.04, d.z), sunDir, 26.0, 1.0, 2.4, 2.0) * 1.2;
    col = cirrusVeil(col, d, sunDir, vec3(1.0, 0.30, 0.25) * 1.4, vec3(0.20, 0.08, 0.22), 0.8, 4.0);
    Cloud cl = cloudCumulus(0.36, 97.0);
    col = cloudVolume(col, d, cl, worldDir(30.0, 1.0), T * 6.0 + vec3(0.30, 0.06, 0.12),
        vec3(0.22, 0.10, 0.30), vec3(0.45, 0.14, 0.22), haze, 0.0);

    col = grade(col, 1.10, 0.0);
    OutColor = skyOutput(col, d);
}
