#version 330
#extension GL_ARB_separate_shader_objects : require

#include <vesence:sky_common.glsl>

layout(location = 0) out vec4 OutColor;

// GLACIER — арктический ясный холод: хрустальный синий купол, низкое острое
// солнце, 22-градусное гало с паргелиями и алмазная пыль.
void main() {
    vec3 d = worldRay(gl_FragCoord.xy);
    vec3 sunDir = worldDir(30.0, 11.0);
    vec3 T = sunTransmit(sunDir, 0.2);

    vec3 col = atmosphere(d, sunDir, 22.0, 1.1, 0.2, 1.0);
    col *= vec3(0.88, 0.98, 1.06);
    col += sunDisc(d, sunDir, 0.017, T);

    float ang = degrees(acos(clamp(dot(d, sunDir), -1.0, 1.0)));
    float ring = smoothstep(21.0, 22.0, ang) * (1.0 - smoothstep(22.0, 24.0, ang));
    vec3 ringCol = mix(vec3(1.0, 0.55, 0.45), vec3(0.55, 0.75, 1.0), smoothstep(21.0, 24.0, ang));
    col += ringCol * ring * 0.12;
    for (int i = 0; i < 2; i++) {
        float sgn = i == 0 ? 1.0 : -1.0;
        vec3 dog = worldDir(30.0 + sgn * 22.5, 11.0);
        float a = acos(clamp(dot(d, dog), -1.0, 1.0));
        col += mix(vec3(1.0, 0.5, 0.35), vec3(0.6, 0.8, 1.0), smoothstep(0.0, 0.05, a)) * (bell(a, 0.014) * 0.9 + bell(a, 0.05) * 0.15);
    }

    float dust = particleShell(d, 6.0, 0.7, 0.05, vec3(0.1, -0.15, 0.05), 3.0, 0.0)
               + particleShell(d, 11.0, 1.1, 0.055, vec3(0.1, -0.12, 0.05), 9.0, 0.0) * 0.8;
    col += vec3(0.85, 0.95, 1.0) * dust * 1.2 * smoothstep(0.0, 0.2, d.y);

    vec3 haze = SkyPass > 1.5 ? vec3(0.0) : atmosphere(vec3(d.x, 0.03, d.z), sunDir, 22.0, 1.1, 0.4, 1.0);
    Cloud cl = cloudStratus(0.18, 61.0);
    cl.bot = 3.2; cl.top = 4.2;
    col = cloudVolume(col, d, cl, sunDir, T * 3.2, skyAmbient(sunDir) * 0.6, vec3(0.2, 0.25, 0.34) * 0.5, haze, 0.0);

    col = grade(col, 1.05, 0.0);
    OutColor = skyOutput(col, d);
}
