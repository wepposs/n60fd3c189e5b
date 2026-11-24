#version 330
#extension GL_ARB_separate_shader_objects : require

#include <vesence:sky_common.glsl>

layout(location = 0) out vec4 OutColor;

// VOLCANO — извержение: светящаяся лавовая кромка у горизонта, колонна пепла
// из жерла, вулканические молнии в пепле, летящие угольки. Солнце задушено.
void main() {
    vec3 d = worldRay(gl_FragCoord.xy);
    float h = max(d.y, 0.0);
    float az = atan(d.x, d.z);
    float vent = pow(max(cos(az - 0.6), 0.0), 3.0);

    vec3 col = skyGradient(d, vec3(0.30, 0.09, 0.04), vec3(0.06, 0.035, 0.03), vec3(0.015, 0.012, 0.014));
    col += vec3(1.0, 0.30, 0.05) * exp(-h * 9.0) * (0.15 + 0.85 * vent) * 0.7;
    col += vec3(1.0, 0.55, 0.15) * exp(-h * 14.0) * vent * 0.6;
    // Задушенное солнце.
    vec3 sunDir = worldDir(150.0, 30.0);
    col += vec3(0.8, 0.25, 0.1) * (bell(acos(clamp(dot(d, sunDir), -1.0, 1.0)), 0.03) * 0.5 + bell(acos(clamp(dot(d, sunDir), -1.0, 1.0)), 0.14) * 0.12);

    float f1 = 0.0;
    vec3 bolts = lightning(d, 5.0, f1);
    vec3 haze = vec3(0.07, 0.03, 0.02);
    Cloud ash = Cloud(0.40, 1.4, 2.2, 1.0, 3.4, 31.0, 0.016, 0.9, 0.0);
    col = cloudVolume(col, d, ash, worldDir(degrees(-0.6) + 90.0, 2.0), vec3(1.0, 0.30, 0.06) * 3.4,
        vec3(0.030, 0.022, 0.024), vec3(0.26, 0.07, 0.015) * (0.35 + 0.65 * vent), haze, f1 * 1.4);
    // Колонна пепла над жерлом.
    vec3 ventDir = worldDir(degrees(-0.6) + 0.0, 5.0);
    float col2 = bell(dot(normalize(vec3(d.x, 0.0, d.z)), normalize(vec3(ventDir.x, 0.0, ventDir.z))) - 1.0, 0.012);
    col = mix(col, vec3(0.05, 0.035, 0.032) + vec3(0.55, 0.14, 0.03) * exp(-h * 8.0), col2 * smoothstep(0.0, 0.2, d.y) * 0.4);
    col += bolts * vec3(1.0, 0.8, 1.0) * 1.4;

    float embers = particleShell(d, 5.0, 0.8, 0.035, vec3(0.5, 0.8, 0.2), 7.0, 0.0)
                 + particleShell(d, 11.0, 1.5, 0.04, vec3(0.5, 0.6, 0.2), 17.0, 0.0) * 0.8;
    col += vec3(1.0, 0.42, 0.08) * embers * 2.0;

    col = grade(col, 1.1, 0.0);
    OutColor = skyOutput(col, d);
}
