#version 330
#extension GL_ARB_separate_shader_objects : require

#include <vesence:sky_common.glsl>

layout(location = 0) out vec4 OutColor;

// MOON — спокойная лунная ночь: фотографическая луна с кратерами и морями,
// холодное небо от лунного света, рваные облака с серебряной кромкой,
// Млечный Путь и звёзды, приглушённые вокруг луны.
void main() {
    vec3 d = worldRay(gl_FragCoord.xy);
    vec3 moonDir = worldDir(32.0, 34.0);

    vec3 col = skyGradient(d, vec3(0.040, 0.062, 0.115), vec3(0.014, 0.026, 0.065), vec3(0.005, 0.010, 0.032));
    float glow = pow(max(dot(d, moonDir), 0.0), 8.0);
    col += vec3(0.10, 0.15, 0.28) * glow * 0.45 + vec3(0.05, 0.08, 0.15) * pow(max(dot(d, moonDir), 0.0), 2.0) * 0.3;

    float starDim = 1.0 - smoothstep(0.0, 0.5, glow * 3.0);
    col += milkyWay(d, normalize(vec3(0.6, 0.3, -0.7)), 0.45) * starDim;
    col += stars(d, 0.9) * starDim;
    col = moonDisc(col, d, moonDir, 0.034, vec3(1.0, 0.97, 0.90) * 1.6, 0.35);

    vec3 haze = vec3(0.05, 0.075, 0.13);
    Cloud cl = cloudCumulus(0.30, 5.0);
    col = cloudVolume(col, d, cl, moonDir, vec3(0.55, 0.62, 0.80) * 0.9,
        vec3(0.05, 0.07, 0.13), vec3(0.025, 0.035, 0.07), haze, 0.0);

    col = grade(col, 1.05, 0.0);
    OutColor = skyOutput(col, d);
}
