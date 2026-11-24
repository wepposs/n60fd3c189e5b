#version 330
#extension GL_ARB_separate_shader_objects : require

#include <vesence:sky_common.glsl>

layout(location = 0) out vec4 OutColor;

// ECLIPSE — полное затмение: чёрный диск в многослойной короне со
// стримерами, бриллиантовое кольцо, звёзды днём, янтарное кольцо на горизонте.
void main() {
    vec3 d = worldRay(gl_FragCoord.xy);
    vec3 sunDir = worldDir(30.0, 52.0);

    vec3 col = skyGradient(d, vec3(0.80, 0.34, 0.10) * 0.55, vec3(0.045, 0.05, 0.12), vec3(0.005, 0.009, 0.030));
    float h = max(d.y, 0.0);
    col += vec3(1.0, 0.45, 0.12) * exp(-h * 14.0) * 0.5;
    col += stars(d, 0.7) * smoothstep(0.05, 0.4, d.y);

    float R = 0.030;
    vec2 uv = tangentUV(d, sunDir) / R;
    float r = length(uv);
    float ang = atan(uv.y, uv.x);

    vec3 ring = vec3(cos(ang), sin(ang), 0.0);
    float streamers = fbm(ring * 2.2 + vec3(0.0, 0.0, r * 0.22 + 3.0), 4);
    float plumes = pow(fbm(ring * 5.0 + 11.0, 3), 2.0);
    float fall = exp(-(r - 1.0) * 0.55);
    float corona = fall * (0.35 + 1.5 * streamers * streamers + 0.8 * plumes) * smoothstep(1.0, 1.04, r);
    corona += exp(-(r - 1.0) * 3.2) * 1.6 * smoothstep(1.0, 1.03, r);
    corona *= 1.0 + 0.06 * sin(ang * 3.0 + Time * 0.2);
    col += vec3(1.00, 0.96, 0.90) * corona * 0.9;

    // Бриллиантовое кольцо.
    float flash = 0.5 + 0.5 * sin(Time * 0.15);
    vec2 p0 = vec2(cos(0.9), sin(0.9));
    float dd = length(uv - p0);
    col += vec3(1.0, 0.97, 0.92) * (bell(dd, 0.05) * 7.0 + bell(dd, 0.35) * 0.9) * (0.5 + 0.5 * flash);

    col *= smoothstep(0.985, 1.0, r);
    col = grade(col, 1.05, 0.0);
    OutColor = skyOutput(col, d);
}
