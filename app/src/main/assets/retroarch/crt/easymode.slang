#version 450

layout(set = 0, binding = 0, std140) uniform UBO
{
   mat4 MVP;
};

#pragma name Easymode
#pragma format R8G8B8A8_UNORM

#pragma stage vertex
layout(location = 0) in vec4 Position;
layout(location = 1) in vec2 TexCoord;
layout(location = 0) out vec2 vTexCoord;
void main()
{
   gl_Position = MVP * Position;
   vTexCoord = TexCoord;
}

#pragma stage fragment
layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 FragColor;
layout(binding = 1) uniform sampler2D Source;
void main()
{
   // Scanline effect: darken every other output row so the CRT preset is visibly distinct from the
   // interpolation presets. textureSize() keeps the shader self-contained (no librashader uniforms).
   vec4 c = texture(Source, vTexCoord);
   float row = fract(vTexCoord.y * float(textureSize(Source, 0).y));
   float scan = 0.75 + 0.25 * step(0.5, row);
   FragColor = c * scan;
}
