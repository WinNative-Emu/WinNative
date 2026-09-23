#version 450

// =============================================================================
//  HDR composition: one encoding for the whole scene (waylandcomp/src/hdr_compose.c).
//
//  The compositor composes with blits, which copy values and know nothing about what they mean.
//  When an HDR game (PQ-encoded BT.2020 frames) shares the scene with SDR content (sRGB windows, the
//  desktop, a window above the game), every draw is first blitted 1:1 into a 10-bit "mixed" image -
//  each pixel still in its own encoding - and this pass turns the mixed image into ONE encoding:
//
//    mode 0 (HDR out, PQ BT.2020): HDR pixels pass through; SDR pixels are decoded (sRGB),
//           converted BT.709 -> BT.2020 and placed at the SDR reference white (params.x nits,
//           203 = Report ITU-R BT.2408), then PQ-encoded.
//    mode 1 (SDR out, sRGB): SDR pixels pass through; HDR pixels are PQ-decoded, converted
//           BT.2020 -> BT.709, tone-mapped relative to the SDR reference white (linear below a
//           knee, a smooth roll-off above it that puts the content's peak, params.y nits, at 98 %)
//           and sRGB-encoded - what a display that cannot take HDR gets instead of washed-out PQ.
//
//  Which encoding a pixel is in: the topmost draw covering it decides. params.w packs the number of
//  rects (count, 0..6) and a bitmask of which of them are HDR (count + 16 * mask); rects are scene
//  pixels (x0, y0, x1, y1), TOP draw first, listed down to the lowest HDR draw (everything below
//  that is SDR, and so is any pixel no listed rect covers).
//
//  Conventions match upscale.vert / the effect passes: combined-image-sampler at binding 0, the
//  push-constant block leads with vec4 ndc (offset 0). The target is exactly scene-sized, so
//  gl_FragCoord is the scene pixel and texelFetch reads the mixed image 1:1 (no filtering).
//  Float-only mask arithmetic (no bitwise ops).
// =============================================================================

layout(binding = 0) uniform sampler2D Mixed;

layout(push_constant) uniform PC {
    vec4 ndc;       // upscale.vert's quad
    vec4 params;    // x = SDR white nits, y = HDR content peak nits (mode 1), z = mode, w = count + 16 * mask
    vec4 rects[6];  // top-first scene rects
} pc;

layout(location = 0) in vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

const float PQ_M1 = 0.1593017578125;
const float PQ_M2 = 78.84375;
const float PQ_C1 = 0.8359375;
const float PQ_C2 = 18.8515625;
const float PQ_C3 = 18.6875;

// Column-major: each group of three is one COLUMN (the coefficients of R, then of G, then of B).
const mat3 BT709_TO_BT2020 = mat3(0.6274, 0.0691, 0.0164,
                                  0.3293, 0.9195, 0.0880,
                                  0.0433, 0.0114, 0.8956);
const mat3 BT2020_TO_BT709 = mat3( 1.6605, -0.1246, -0.0182,
                                  -0.5876,  1.1329, -0.1006,
                                  -0.0728, -0.0083,  1.1187);

vec3 srgbToLinear(vec3 c) {
    vec3 lo = c / 12.92;
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(lo, hi, step(vec3(0.04045), c));
}

vec3 linearToSrgb(vec3 c) {
    vec3 lo = c * 12.92;
    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, step(vec3(0.0031308), c));
}

// PQ code (0..1) -> luminance / 10000 cd/m2 (SMPTE ST 2084 EOTF).
vec3 pqToLinear(vec3 e) {
    vec3 p = pow(clamp(e, 0.0, 1.0), vec3(1.0 / PQ_M2));
    return pow(max(p - PQ_C1, 0.0) / max(PQ_C2 - PQ_C3 * p, 1e-6), vec3(1.0 / PQ_M1));
}

// luminance / 10000 cd/m2 -> PQ code (inverse EOTF).
vec3 linearToPq(vec3 y) {
    vec3 p = pow(clamp(y, 0.0, 1.0), vec3(PQ_M1));
    return pow((PQ_C1 + PQ_C2 * p) / (1.0 + PQ_C3 * p), vec3(PQ_M2));
}

// Tone curve in units of SDR white: identity up to the knee, then an exponential roll-off towards 1
// whose rate puts `peak` (also in SDR-white units) at 98 % of the range.
float toneCurve(float x, float peak) {
    const float knee = 0.8;
    if (x <= knee) return x;
    float span = max(peak - knee, 0.05);
    float rate = max(span / 3.912, 1.0 - knee); // ln(50) = 3.912; never steeper than the identity
    return knee + (1.0 - knee) * (1.0 - exp(-(x - knee) / rate));
}

bool isHdrPixel(vec2 p) {
    float packed = pc.params.w;
    float count = mod(packed, 16.0);
    float mask = floor(packed / 16.0);
    for (int i = 0; i < 6; i++) {
        if (float(i) >= count - 0.5) break;
        vec4 r = pc.rects[i];
        if (p.x >= r.x && p.y >= r.y && p.x < r.z && p.y < r.w) {
            float bit = mod(floor(mask / exp2(float(i))), 2.0);
            return bit > 0.5;
        }
    }
    return false;
}

void main() {
    vec3 c = texelFetch(Mixed, ivec2(gl_FragCoord.xy), 0).rgb;
    bool hdr = isHdrPixel(gl_FragCoord.xy);
    float sdrWhite = max(pc.params.x, 1.0);
    vec3 outc;
    if (pc.params.z < 0.5) {
        // HDR out: PQ BT.2020.
        if (hdr) {
            outc = c;
        } else {
            vec3 nits = (BT709_TO_BT2020 * srgbToLinear(c)) * sdrWhite;
            outc = linearToPq(nits / 10000.0);
        }
    } else {
        // SDR out: sRGB BT.709, HDR tone-mapped.
        if (!hdr) {
            outc = c;
        } else {
            vec3 rel = max(BT2020_TO_BT709 * (pqToLinear(c) * 10000.0), 0.0) / sdrWhite;
            float m = max(max(rel.r, rel.g), rel.b);
            if (m > 1e-6) rel *= toneCurve(m, max(pc.params.y, sdrWhite) / sdrWhite) / m;
            outc = linearToSrgb(clamp(rel, 0.0, 1.0));
        }
    }
    outColor = vec4(outc, 1.0);
}
