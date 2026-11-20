#version 300 es
in vec4 aFramePosition;
in vec4 aTexSamplingCoord;
uniform mat4 uMvp;
out vec2 vTex;

void main() {
    gl_Position = uMvp * aFramePosition;
    vTex = aTexSamplingCoord.xy;
}
