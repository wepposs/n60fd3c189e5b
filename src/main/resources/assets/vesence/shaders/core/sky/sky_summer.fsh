#version 330
#extension GL_ARB_separate_shader_objects : require

#include <vesence:sky_common.glsl>

layout(location = 0) out vec4 OutColor;

// SUMMER — полдень: настоящая атмосфера (Рэлей + Ми + озон), белое солнце,
// объёмные кучевые облака с тёмными основаниями и светлыми шапками, высокая
// перистая вуаль и лёгкая дымка у горизонта.
void main() {
    vec3 d = worldRay(gl_FragCoord.xy);
    vec3 sunDir = worldDir(30.0, 52.0);
    vec3 T = sunTransmit(sunDir, 0.35);

    vec3 col = atmosphere(d, sunDir, 22.0, 1.0, 0.35, 1.0);
    col += sunDisc(d, sunDir, 0.017, T);

    vec3 haze = SkyPass > 1.5 ? vec3(0.0) : atmosphere(vec3(d.x, 0.03, d.z), sunDir, 22.0, 1.0, 0.35, 1.0) * 1.05;
    col = cirrusVeil(col, d, sunDir, vec3(1.0, 0.98, 0.95) * 0.85, vec3(0.55, 0.65, 0.85), 0.18, 8.0);

    Cloud cl = cloudCumulus(0.38, 3.0);
    col = cloudVolume(col, d, cl, sunDir, T * 3.4,
        skyAmbient(sunDir) * 0.62, vec3(0.20, 0.22, 0.26) * 0.55, haze, 0.0);

    col = grade(col, 1.08, 0.0);
    OutColor = skyOutput(col, d);
}
