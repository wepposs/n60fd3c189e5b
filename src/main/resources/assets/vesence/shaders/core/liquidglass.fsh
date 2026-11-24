#version 150

// Жидкое стекло в стиле rockstar: преломление фона у кромки (линза),
// хроматическая аберрация, тёмное тело и fresnel-ободок. Сэмплит
// Sampler0 = текстуру main framebuffer (Renderer2D её биндит).

in vec2 FragCoord;   // 0-1 внутри прямоугольника
in vec2 TexCoord;    // UV экрана
in vec4 FragColor;
in vec2 SizeOut;
in float DistortStrength;

uniform sampler2D Sampler0;
uniform vec2 Size;
uniform float CornerSmoothness;
uniform vec4 Radius;          // r1..r4 (одинаковые — заполнит Java)
uniform float GlobalAlpha;

out vec4 fragColor;

// SDF скруглённого прямоугольника.
float sdRoundBox(vec2 p, vec2 b, vec4 r) {
    r.xy = (p.x > 0.0) ? r.xy : r.zw;
    r.x  = (p.y > 0.0) ? r.x : r.y;
    vec2 q = abs(p) - b + r.x;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r.x;
}

void main() {
    vec2 center = Size * 0.5;
    vec2 halfSize = center - 1.0;
    vec2 pixelPos = FragCoord * Size - center;

    // Маска формы с мягкой кромкой.
    float dist = sdRoundBox(pixelPos, halfSize, Radius);
    float shapeMask = 1.0 - smoothstep(-1.0, 1.0, dist);
    if (shapeMask < 0.001) discard;

    // Нормаль: градиент SDF указывает наружу — преломляем внутрь.
    vec2 eps = vec2(1.5, 0.0);
    float distX = sdRoundBox(pixelPos + eps.xy, halfSize, Radius);
    float distY = sdRoundBox(pixelPos + eps.yx, halfSize, Radius);
    vec2 gradient = vec2(distX - dist, distY - dist);
    vec2 normal = length(gradient) > 1e-4 ? normalize(gradient) : vec2(0.0);

    // Сила преломления растёт к кромке: мягкий подъём + резкий пик у края.
    float innerDist = clamp(-dist / max(min(halfSize.x, halfSize.y), 1.0), 0.0, 1.0);
    float edgeFactor = 1.0 - innerDist;
    float responseCurve = smoothstep(0.0, 1.0, edgeFactor * 1.2);
    float edgePeak = smoothstep(0.6, 1.0, edgeFactor);
    float strength = mix(responseCurve * 0.6, 1.0, edgePeak);

    // Смещение UV: нормаль * сила * шаг (в экранных UV).
    float refractStrength = strength * DistortStrength;
    vec2 refractDir = normal * refractStrength * 0.5;

    // Хроматическая аберрация: красный смещается сильнее, синий слабее.
    float ab = 0.25;
    vec3 col;
    col.r = texture(Sampler0, clamp(TexCoord - refractDir * (1.0 + ab), 0.0, 1.0)).r;
    col.g = texture(Sampler0, clamp(TexCoord - refractDir,              0.0, 1.0)).g;
    col.b = texture(Sampler0, clamp(TexCoord - refractDir * (1.0 - ab), 0.0, 1.0)).b;

    // Тон стекла: tint из FragColor, приглушённый как тёмное стекло iOS.
    col *= FragColor.rgb;
    col *= 0.65;

    // Fresnel-ободок: светлый тонкий контур у кромки.
    float fresnel = smoothstep(0.5, 1.0, edgeFactor) * 0.18;
    col += vec3(fresnel);

    float finalAlpha = FragColor.a * shapeMask * GlobalAlpha;
    if (finalAlpha < 0.001) discard;
    fragColor = vec4(col, finalAlpha);
}
