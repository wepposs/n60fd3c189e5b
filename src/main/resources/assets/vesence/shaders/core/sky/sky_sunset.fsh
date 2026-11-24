#version 330
#extension GL_ARB_separate_shader_objects : require

#include <vesence:sky_common.glsl>

layout(location = 0) out vec4 OutColor;

// SUNSET — золотой час: низкое солнце сквозь плотный воздух, янтарно-розовая
// полоса, облака с янтарной подсветкой снизу и тёмными верхами, перистые горят,
// лучи света в разрывах облаков, первые звёзды в зените.
void main() {
    vec3 d = worldRay(gl_FragCoord.xy);
    vec3 sunDir = worldDir(30.0, 3.0);
    vec3 T = sunTransmit(sunDir, 0.9);

    vec3 col = atmosphere(d, sunDir, 22.0, 1.0, 0.9, 1.0);
    col += stars(d, 0.7) * smoothstep(0.06, 0.01, luma(col));
    col += sunDisc(d, sunDir, 0.018, T);

    vec3 haze = SkyPass > 1.5 ? vec3(0.0) : atmosphere(vec3(d.x, 0.04, d.z), sunDir, 22.0, 1.0, 0.9, 1.0) * 1.1;
    col = cirrusVeil(col, d, sunDir, T * 2.4 + vec3(0.2, 0.08, 0.05), vec3(0.22, 0.20, 0.36), 0.75, 4.0);

    Cloud cl = cloudCumulus(0.45, 7.0);
    float shafts = cloudShafts(d, sunDir, cl);
    col = cloudVolume(col, d, cl, sunDir, T * 7.0,
        vec3(0.36, 0.30, 0.52) * 1.0, vec3(0.95, 0.42, 0.20) * 0.85, haze, 0.0);
    col += T * pow(max(dot(d, sunDir), 0.0), 6.0) * shafts * 0.16;

    col = grade(col, 1.10, 0.0);
    OutColor = skyOutput(col, d);
}
