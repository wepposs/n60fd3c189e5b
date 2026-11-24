#version 330
#extension GL_ARB_separate_shader_objects : require

#include <vesence:sky_common.glsl>

layout(location = 0) out vec4 OutColor;

// DAWN — рассвет: солнце у кромки, персик и роза у горизонта, холодный синий
// выше, облака с тёплой подсветкой снизу, тают последние звёзды.
void main() {
    vec3 d = worldRay(gl_FragCoord.xy);
    vec3 sunDir = worldDir(40.0, 4.0);
    vec3 T = sunTransmit(sunDir, 0.7);

    vec3 col = atmosphere(d, sunDir, 20.0, 1.0, 0.7, 1.3);
    col += stars(d, 0.6) * smoothstep(0.05, 0.005, luma(col));
    col += sunDisc(d, sunDir, 0.017, T);

    vec3 haze = SkyPass > 1.5 ? vec3(0.0) : atmosphere(vec3(d.x, 0.04, d.z), sunDir, 20.0, 1.0, 0.7, 1.3) * 1.1;
    col = cirrusVeil(col, d, sunDir, T * 2.0 + vec3(0.18, 0.08, 0.10), vec3(0.20, 0.22, 0.40), 0.7, 6.0);

    Cloud cl = cloudCumulus(0.36, 12.0);
    float shafts = cloudShafts(d, sunDir, cl);
    col = cloudVolume(col, d, cl, sunDir, T * 4.2,
        vec3(0.30, 0.30, 0.50) * 0.55, vec3(0.60, 0.30, 0.25) * 0.28, haze, 0.0);
    col += T * pow(max(dot(d, sunDir), 0.0), 6.0) * shafts * 0.14;

    col = grade(col, 1.08, 0.0);
    OutColor = skyOutput(col, d);
}
