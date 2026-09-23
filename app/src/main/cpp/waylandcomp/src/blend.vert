#version 450
// One surface as a quad: dst = the target rectangle in NDC, src = the buffer rectangle in UV.
layout(push_constant) uniform PC { vec4 dst; vec4 src; } pc;
layout(location = 0) out vec2 uv;

void main() {
    vec2 t = vec2(gl_VertexIndex & 1, gl_VertexIndex >> 1);
    gl_Position = vec4(mix(pc.dst.xy, pc.dst.zw, t), 0.0, 1.0);
    uv = mix(pc.src.xy, pc.src.zw, t);
}
