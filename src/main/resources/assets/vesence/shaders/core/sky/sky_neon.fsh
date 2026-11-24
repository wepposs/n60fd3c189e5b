#version 330
#extension GL_ARB_separate_shader_objects : require

#include <vesence:sky_common.glsl>

layout(location = 0) out vec4 OutColor;

// NEON — ночь над мегаполисом: натриевая засветка снизу, низкие облака
// подсвечены тёплым городским светом, у горизонта розово-фиолетовые
// оттенки LED, звёзд почти нет.
void main() {
    vec3 d = worldRay(gl_FragCoord.xy);
    float h = max(d.y, 0.0);
    float az = atan(d.x, d.z);
    vec3 col = skyGradient(d, vec3(0.16, 0.09, 0.07), vec3(0.030, 0.024, 0.040), vec3(0.005, 0.006, 0.013));
    float city = 0.75 + 0.25 * sin(az * 1.3 + 0.7);
    col += vec3(1.0, 0.55, 0.28) * exp(-h * 6.0) * 0.22 * city;
    col += vec3(0.75, 0.35, 0.80) * exp(-h * 16.0) * 0.10 * (1.0 - city * 0.5);
    col += stars(d, 0.30) * smoothstep(0.35, 0.85, d.y);

    vec3 haze = vec3(0.12, 0.07, 0.06);
    Cloud cl = Cloud(0.52, 1.3, 1.2, 1.0, 2.4, 71.0, 0.012, 0.8, 0.4);
    col = cloudVolume(col, d, cl, vec3(0.0, 1.0, 0.0), vec3(0.0),
        vec3(0.010, 0.010, 0.018), vec3(0.62, 0.34, 0.24) * city, haze, 0.0);

    col = grade(col, 1.05, 0.0);
    OutColor = skyOutput(col, d);
}
