#version 330
#extension GL_ARB_separate_shader_objects : require

#include <vesence:sky_common.glsl>

layout(location = 0) out vec4 OutColor;

// INFERNO — ад: огонь светит снизу, клубы копоти с раскалёнными основаниями,
// два слоя угольков, летящих вверх. Всё дышит.
void main() {
    vec3 d = worldRay(gl_FragCoord.xy);
    float breath = 0.86 + 0.14 * sin(Time * 1.1) + 0.06 * sin(Time * 2.7 + 1.3);

    vec3 col = skyGradient(d, vec3(0.45, 0.10, 0.025), vec3(0.05, 0.014, 0.010), vec3(0.018, 0.008, 0.008));
    float h = max(d.y, 0.0);
    col += vec3(1.0, 0.32, 0.06) * exp(-h * 7.0) * 0.55 * breath;
    col += vec3(1.0, 0.55, 0.12) * exp(-h * 9.0) * 0.5 * breath;
    // Столбы огня у горизонта.
    float az = atan(d.x, d.z);
    float flames = pow(fbm(vec2(az * 6.0, Time * 0.35), 3), 2.5);
    col += vec3(1.0, 0.45, 0.08) * flames * exp(-h * 6.0) * 1.1 * breath;

    vec3 haze = vec3(0.13, 0.035, 0.012);
    Cloud soot = Cloud(0.55, 1.3, 1.4, 1.0, 3.0, 13.0, 0.020, 0.8, 0.1);
    col = cloudVolume(col, d, soot, worldDir(10.0, 3.0), vec3(1.0, 0.32, 0.06) * 3.0 * breath,
        vec3(0.030, 0.012, 0.010), vec3(0.50, 0.13, 0.025) * breath, haze, 0.0);

    float embers = particleShell(d, 5.0, 0.8, 0.05, vec3(0.2, 1.1, 0.1), 3.0, 0.015)
                 + particleShell(d, 10.0, 1.4, 0.055, vec3(0.2, 0.9, 0.1), 9.0, 0.0) * 0.8;
    col += vec3(1.0, 0.45, 0.10) * embers * 2.2;

    col = grade(col, 1.15, 0.0);
    OutColor = skyOutput(col, d);
}
