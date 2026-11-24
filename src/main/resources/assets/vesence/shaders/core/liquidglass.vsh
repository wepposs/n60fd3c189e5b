#version 150

// Версия по образцу liquidglass/vertex.vsh (рабочей): только те атрибуты,
// которые реально биндит Renderer2D.setupLiquidGlassAttributes —
// Position (3f), UV0 (2f), Color (4f). Все параметры стекла — юниформы.

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat3 uMatrix;
uniform vec2 uViewport;
uniform vec2 Size;
uniform float GlassDistortionStrength;

out vec2 FragCoord;
out vec2 TexCoord;
out vec4 FragColor;
out vec2 SizeOut;
out float DistortStrength;

void main() {
    vec3 worldPos = uMatrix * vec3(Position.xy, 1.0);
    gl_Position = vec4((worldPos.x / uViewport.x) * 2.0 - 1.0,
                       1.0 - (worldPos.y / uViewport.y) * 2.0,
                       0.0, 1.0);
    FragCoord = UV0; // 0-1 внутри прямоугольника
    TexCoord = vec2(worldPos.x / uViewport.x, 1.0 - (worldPos.y / uViewport.y)); // UV экрана
    FragColor = Color;
    SizeOut = Size;
    DistortStrength = GlassDistortionStrength;
}
