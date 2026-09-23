#version 450
// Wayland buffers carry premultiplied alpha; the pipeline blends ONE, ONE_MINUS_SRC_ALPHA.
layout(binding = 0) uniform sampler2D Surface;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = texture(Surface, uv);
}
