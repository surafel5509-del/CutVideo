#version 300 es
precision mediump float;
in vec2 vTex;
out vec4 fragColor;
uniform sampler2D uTexSampler;
uniform float uAlpha;

void main() {
    vec4 c = texture(uTexSampler, vTex);
    fragColor = vec4(c.rgb, c.a * uAlpha);
}
