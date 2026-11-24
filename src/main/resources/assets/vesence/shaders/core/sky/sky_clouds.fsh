#version 330
#extension GL_ARB_separate_shader_objects : require

#include <vesence:sky_common.glsl>

layout(location = 0) out vec4 OutColor;

// CLOUDS — небо про облака: плотный объёмный ярус с контрастом света и тени,
// над ним высокий полупрозрачный ярус, лучи сквозь разрывы и тёплая дымка.
void main() {
    vec3 d = worldRay(gl_FragCoord.xy);
    vec3 sunDir = worldDir(140.0, 34.0);
    vec3 T = sunTransmit(sunDir, 0.5);

    vec3 col = atmosphere(d, sunDir, 22.0, 1.0, 0.5, 1.0);
    col += sunDisc(d, sunDir, 0.017, T);
    vec3 haze = SkyPass > 1.5 ? vec3(0.0) : atmosphere(vec3(d.x, 0.03, d.z), sunDir, 22.0, 1.0, 0.5, 1.0) * 1.05;

    Cloud hi = Cloud(0.40, 1.0, 0.6, 4.6, 5.6, 11.0, 0.006, 0.6, 0.9);
    col = cloudVolume(col, d, hi, sunDir, T * 3.0, skyAmbient(sunDir) * 0.55, vec3(0.20, 0.22, 0.28) * 0.5, haze, 0.0);

    Cloud lo = cloudCumulus(0.56, 5.0);
    lo.top = 3.8;
    lo.dens = 0.95;
    float shafts = cloudShafts(d, sunDir, lo);
    col = cloudVolume(col, d, lo, sunDir, T * 3.6, skyAmbient(sunDir) * 0.60, vec3(0.18, 0.20, 0.26) * 0.55, haze, 0.0);
    col += T * pow(max(dot(d, sunDir), 0.0), 8.0) * shafts * 0.10;

    col = grade(col, 1.06, 0.0);
    OutColor = skyOutput(col, d);
}
