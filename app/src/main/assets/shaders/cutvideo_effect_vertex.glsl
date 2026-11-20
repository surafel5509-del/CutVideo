#version 300 es
// Full-screen triangle strip. Media3 binds the input texture and the output framebuffer, so the
// vertex stage only needs to pass through clip-space positions and texture coordinates.
in vec4 aFramePosition;
in vec4 aTexSamplingCoord;
out vec2 vTex;

void main() {
    gl_Position = aFramePosition;
    vTex = aTexSamplingCoord.xy;
}
