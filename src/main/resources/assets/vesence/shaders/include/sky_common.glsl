// ===========================================================================
//  Vesence sky atelier v2 — shared library for every sky_*.fsh mode.
//
//  ABI with SkyShaderRenderer: exactly 48 bytes, in this exact order.
//
//  Contents
//    1. camera + noise
//    2. physical atmosphere (Rayleigh + Mie + ozone, single scattering)
//    3. sun, moon
//    4. volumetric clouds (raymarched, multi-scatter lit)
//    5. night sky: stars, Milky Way, nebula, aurora, meteors
//    6. particles, rainbow, lightning
//    7. grading and the final composite
//
//  Everything here is written in the GLSL subset shared by 330 core and
//  ES 3.00 (explicit float literals, no implicit int->float conversions).
// ===========================================================================
layout(std140) uniform SkyData {
    vec4 ResTime;
    vec4 CamFov;
    vec4 FogData;
};
#define Resolution ResTime.xy
#define Time ResTime.z
#define Intensity ResTime.w
#define CamYaw CamFov.x
#define CamPitch CamFov.y
#define Fov CamFov.z
#define FogColor FogData.rgb
#define FogBlend FogData.w
// 0 = single full-resolution pass; 1 = clouds-only pass into a low-res HDR
// buffer; 2 = full-resolution sky that composites that buffer.
#define SkyPass CamFov.w

// Low-res cloud buffer: rgb = in-scattered light (HDR), a = transmittance.
uniform sampler2D CloudBuffer;

const float PI  = 3.14159265359;
const float TAU = 6.28318530718;

// ---------------------------------------------------------------------------
//  1. Camera
// ---------------------------------------------------------------------------

vec3 worldRay(vec2 pixel) {
    vec2 p = pixel / max(Resolution, vec2(1.0)) * 2.0 - 1.0;
    float yaw = radians(CamYaw), pitch = radians(CamPitch);
    vec3 forward = vec3(-sin(yaw)*cos(pitch), -sin(pitch), cos(yaw)*cos(pitch));
    vec3 right = vec3(-cos(yaw), 0.0, -sin(yaw));
    float lens = tan(radians(clamp(Fov, 1.0, 179.0)) * 0.5);
    return normalize(forward + lens * (p.x * Resolution.x / max(Resolution.y, 1.0) * right + p.y * cross(right, forward)));
}

// Azimuth/elevation in degrees -> unit vector (azimuth 0 = +Z, 90 = -X).
vec3 worldDir(float az, float elevation) {
    vec2 a = radians(vec2(az, elevation));
    return vec3(-sin(a.x)*cos(a.y), sin(a.y), cos(a.x)*cos(a.y));
}

// ---------------------------------------------------------------------------
//  Hashing and noise
// ---------------------------------------------------------------------------

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}
float hash12(vec2 p) { return hash13(vec3(p, 17.13)); }
float hash11(float p) { return hash13(vec3(p, 3.7, 11.9)); }

vec2 hash22(vec2 p) {
    float h = hash12(p);
    return vec2(h, hash12(p + vec2(h * 17.7, 3.1)));
}
vec3 hash33(vec3 p) {
    float h = hash13(p);
    float j = hash13(p + vec3(h * 19.3, 4.7, 8.1));
    return vec3(h, j, hash13(p + vec3(j * 23.1, h * 5.3, 2.9)));
}

float noise3(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    vec3 u = f*f*f*(f*(f*6.0-15.0)+10.0);
    return mix(mix(mix(hash13(i), hash13(i+vec3(1,0,0)), u.x),
                   mix(hash13(i+vec3(0,1,0)), hash13(i+vec3(1,1,0)), u.x), u.y),
               mix(mix(hash13(i+vec3(0,0,1)), hash13(i+vec3(1,0,1)), u.x),
                   mix(hash13(i+vec3(0,1,1)), hash13(i+vec3(1,1,1)), u.x), u.y), u.z);
}

float noise2(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f*f*f*(f*(f*6.0-15.0)+10.0);
    return mix(mix(hash12(i), hash12(i+vec2(1,0)), u.x),
               mix(hash12(i+vec2(0,1)), hash12(i+vec2(1,1)), u.x), u.y);
}

// Rotated-octave fbm, normalised to 0..1 (mean 0.5).
float fbm(vec3 p, int oct) {
    float a = 0.5, s = 0.0, n = 0.0;
    mat3 r = mat3( 0.00,  0.80,  0.60,
                  -0.80,  0.36, -0.48,
                  -0.60, -0.48,  0.64);
    for (int i = 0; i < 6; i++) {
        if (i >= oct) break;
        s += a * noise3(p);
        n += a;
        p = r * p * 2.02 + vec3(1.7, 9.2, 4.1);
        a *= 0.5;
    }
    return s / max(n, 0.001);
}

float fbm(vec2 p, int oct) {
    float a = 0.5, s = 0.0, n = 0.0;
    mat2 r = mat2(0.80, 0.60, -0.60, 0.80);
    for (int i = 0; i < 6; i++) {
        if (i >= oct) break;
        s += a * noise2(p);
        n += a;
        p = r * p * 2.03 + vec2(1.7, 9.2);
        a *= 0.5;
    }
    return s / max(n, 0.001);
}

// Ridged multifractal, 0..1: sharp crests for smoke, lightning veins, filaments.
float ridged(vec3 p, int oct) {
    float a = 0.5, s = 0.0, n = 0.0;
    mat3 r = mat3( 0.00,  0.80,  0.60,
                  -0.80,  0.36, -0.48,
                  -0.60, -0.48,  0.64);
    for (int i = 0; i < 6; i++) {
        if (i >= oct) break;
        float v = 1.0 - abs(noise3(p) * 2.0 - 1.0);
        s += a * v * v;
        n += a;
        p = r * p * 2.07 + vec3(3.1, 7.7, 1.3);
        a *= 0.5;
    }
    return s / max(n, 0.001);
}

// Cellular noise on a plane: x = distance to nearest point, y = to second,
// z = random id of the nearest cell.
vec3 voronoi2(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    float d1 = 8.0, d2 = 8.0, id = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(float(x), float(y));
            vec2 o = hash22(i + g);
            vec2 r = g + o - f;
            float d = dot(r, r);
            if (d < d1) { d2 = d1; d1 = d; id = hash12(i + g + 7.7); }
            else if (d < d2) { d2 = d; }
        }
    }
    return vec3(sqrt(d1), sqrt(d2), id);
}

// Interleaved gradient noise (Jimenez 2014): a low-discrepancy per-pixel
// offset. Used to jitter raymarches — reads as fine, even texture instead of
// the sandy grain white noise leaves without temporal accumulation.
float ign(vec2 px, float seed) {
    px += vec2(seed * 5.588238, seed * 3.137);
    return fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715))));
}

// Billow noise: rounded "cauliflower" lobes (1 - |n|), the look of cumulus tops.
float billow(vec3 p, int oct) {
    float a = 0.5, s = 0.0, n = 0.0;
    mat3 r = mat3( 0.00,  0.80,  0.60,
                  -0.80,  0.36, -0.48,
                  -0.60, -0.48,  0.64);
    for (int i = 0; i < 5; i++) {
        if (i >= oct) break;
        s += a * (1.0 - abs(noise3(p) * 2.0 - 1.0));
        n += a;
        p = r * p * 2.11 + vec3(5.3, 1.9, 8.7);
        a *= 0.5;
    }
    return s / max(n, 0.001);
}

float remap(float x, float a, float b, float c, float d) {
    return c + (x - a) / max(b - a, 0.00001) * (d - c);
}

float bell(float x, float width) { return exp(-x*x / max(width*width, 0.000001)); }

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

float pixelAngle(vec3 d) { return max(length(fwidth(d)), 0.00005); }

vec2 tangentUV(vec3 d, vec3 center) {
    vec3 up = abs(center.y) > 0.95 ? vec3(0,0,1) : vec3(0,1,0);
    vec3 x = normalize(cross(center, up));
    return vec2(dot(d, x), dot(d, cross(x, center)));
}

vec3 spectral(float t) {
    return 0.50 + 0.50*cos(TAU*(t + vec3(0.0, 0.33, 0.67)));
}

// ---------------------------------------------------------------------------
//  2. Atmosphere
//
//  Single-scattering model on a spherical planet: Rayleigh (blue sky, red
//  sunsets), Mie aerosols (white haze, sun halo) and an ozone layer (the deep
//  blue of twilight). Units are km; the observer stands just above the ground.
//  Cost: 10 view samples x 3 light samples, no textures.
// ---------------------------------------------------------------------------

const float R_G = 6360.0;
const float R_A = 6420.0;
const float H_R = 8.0;
const float H_M = 1.2;
const vec3 BETA_R   = vec3(5.80e-3, 13.5e-3, 33.1e-3);
const vec3 BETA_M_S = vec3(4.0e-3);
const vec3 BETA_M_E = vec3(8.4e-3);
const vec3 BETA_O   = vec3(0.65e-3, 1.88e-3, 0.085e-3);

float raySphereFar(vec3 ro, vec3 rd, float r) {
    float b = dot(ro, rd);
    float c = dot(ro, ro) - r * r;
    return -b + sqrt(max(b * b - c, 0.0));
}

vec3 atmoExtinction(float h, float rayK, float mieK, float ozoneK) {
    return BETA_R * (exp(-h / H_R) * rayK)
         + BETA_M_E * (exp(-h / H_M) * mieK)
         + BETA_O * (max(0.0, 1.0 - abs(h - 25.0) / 15.0) * ozoneK);
}

// Sky radiance towards d. sunI is the sun's radiance scale; mieK / rayK /
// ozoneK scale the three species (1.0 = clear day).
vec3 skyScatter(vec3 d, vec3 sunDir, float sunI, float rayK, float mieK, float ozoneK) {
    vec3 rd = normalize(vec3(d.x, max(d.y, 0.0008), d.z));
    vec3 ro = vec3(0.0, R_G + 0.15, 0.0);
    float tmax = raySphereFar(ro, rd, R_A);

    float mu = dot(rd, sunDir);
    float phaseR = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    const float g = 0.78;
    float phaseM = 3.0 / (8.0 * PI) * ((1.0 - g*g) * (1.0 + mu*mu))
                 / ((2.0 + g*g) * pow(1.0 + g*g - 2.0*g*mu, 1.5));

    vec3 odView = vec3(0.0);
    vec3 sumR = vec3(0.0);
    vec3 sumM = vec3(0.0);
    vec3 sumMS = vec3(0.0);

    float jit = ign(gl_FragCoord.xy, 41.0);
    for (int i = 0; i < 10; i++) {
        float f0 = float(i) / 10.0, f1 = float(i + 1) / 10.0;
        float t0 = tmax * f0 * f0, t1 = tmax * f1 * f1;
        float dt = t1 - t0;
        vec3 p = ro + rd * mix(t0, t1, jit);
        float h = length(p) - R_G;
        float dR = exp(-h / H_R) * dt * rayK;
        float dM = exp(-h / H_M) * dt * mieK;
        odView += atmoExtinction(h, rayK, mieK, ozoneK) * dt;

        float tl = raySphereFar(p, sunDir, R_A);
        vec3 odL = vec3(0.0);
        for (int j = 0; j < 3; j++) {
            float l0 = float(j) / 3.0, l1 = float(j + 1) / 3.0;
            float s0 = tl * l0 * l0, s1 = tl * l1 * l1;
            vec3 q = p + sunDir * (0.5 * (s0 + s1));
            odL += atmoExtinction(length(q) - R_G, rayK, mieK, ozoneK) * (s1 - s0);
        }
        float cp = dot(p, sunDir);
        float perigee = length(p - sunDir * cp);
        float lit = cp > 0.0 ? 1.0 : smoothstep(R_G - 2.0, R_G + 7.0, perigee);
        vec3 att = exp(-(odView + odL)) * lit;
        sumR += dR * att;
        sumM += dM * att;
        // Multiple scattering (Hillaire-style isotropic approximation): light
        // that bounced more than once. It is what keeps twilight luminous and
        // blue instead of collapsing to black right after sunset.
        float soft = smoothstep(R_G - 40.0, R_G + 10.0, perigee) * (cp > 0.0 ? 1.0 : 0.6);
        vec3 odSoft = odView + odL * 0.35;
        sumMS += (dR * BETA_R + dM * BETA_M_S) * exp(-odSoft) * soft;
    }
    vec3 single = sumR * BETA_R * phaseR + sumM * BETA_M_S * phaseM;
    vec3 multi = sumMS * (1.0 / (4.0 * PI)) * vec3(0.42, 0.68, 1.12) * 0.30;
    return sunI * (single + multi);
}

// Colour of the sun as seen through the atmosphere: orange-red at the horizon.
vec3 sunTransmit(vec3 sunDir, float mieK) {
    float e = sunDir.y;
    float zen = min(degrees(acos(clamp(e, -1.0, 1.0))), 92.6);
    float m = 1.0 / (max(e, 0.0) + 0.50572 * pow(max(96.07995 - zen, 0.4), -1.6364));
    vec3 tau = BETA_R * H_R + BETA_M_E * H_M * mieK + BETA_O * 30.0;
    return exp(-tau * m) * smoothstep(-0.075, 0.02, e);
}

// Soft ambient light from the sky dome, by sun elevation (cheap fit of the
// scatter model): blue by day, rose at dusk, deep indigo after.
vec3 skyAmbient(vec3 sunDir) {
    float e = sunDir.y;
    float day = smoothstep(-0.02, 0.45, e);
    vec3 dusk = vec3(0.34, 0.26, 0.42);
    vec3 noon = vec3(0.30, 0.46, 0.80);
    vec3 night = vec3(0.025, 0.035, 0.08);
    vec3 c = mix(night, dusk, smoothstep(-0.28, 0.0, e));
    return mix(c, noon, day) * (0.35 + 0.65 * smoothstep(-0.3, 0.3, e));
}

// Dome wrapper: physical scatter above the horizon; below it, a darker
// copy of the horizon colour so the lower half never glows like the sky.
vec3 atmosphere(vec3 d, vec3 sunDir, float sunI, float rayK, float mieK, float ozoneK) {
    vec3 c = skyScatter(d, sunDir, sunI, rayK, mieK, ozoneK);
    float below = smoothstep(0.0, -0.10, d.y);
    return mix(c, c * vec3(0.30, 0.36, 0.48), below);
}

vec3 atmosphere(vec3 d, vec3 sunDir) {
    return atmosphere(d, sunDir, 22.0, 1.0, 1.0, 1.0);
}

// Hand-painted gradient for stylised modes: horizon -> middle -> zenith.
vec3 skyGradient(vec3 d, vec3 horizon, vec3 middle, vec3 zenith) {
    float h = max(d.y, 0.0);
    vec3 c = mix(horizon, middle, smoothstep(0.0, 0.34, h));
    c = mix(c, zenith, smoothstep(0.14, 0.95, h));
    vec3 low = mix(horizon * 0.5, vec3(luma(horizon)) * 0.5, 0.5);
    return mix(c, low, smoothstep(0.0, 0.14, -d.y) * 0.9);
}

// Mie forward-scatter lobe for gradient skies.
vec3 sunGlowGradient(vec3 d, vec3 sunDir, vec3 tint, float amount) {
    float mu = max(dot(d, sunDir), 0.0);
    float h = max(d.y, 0.0);
    float s = pow(mu, 4.0) * 0.14 + pow(mu, 24.0) * 0.22 + pow(mu, 200.0) * 0.30;
    return tint * s * amount * (0.55 + 0.45 * exp(-h * 2.0));
}

// ---------------------------------------------------------------------------
//  3. Sun and moon
// ---------------------------------------------------------------------------

// Sun: limb-darkened disc (HDR, blows out to white under the tonemap) plus
// aureole. `col` is the transmitted sun colour, so low suns burn orange.
vec3 sunDisc(vec3 d, vec3 s, float radius, vec3 col) {
    float a = acos(clamp(dot(d, s), -1.0, 1.0));
    float aa = pixelAngle(d);
    float disc = 1.0 - smoothstep(radius - aa, radius + aa, a);
    // Limb darkening with the real colour shift: the centre burns white,
    // the edge is dimmer and warmer (cooler, higher layers of the photosphere).
    float mu = sqrt(max(1.0 - (a / radius) * (a / radius), 0.0));
    vec3 limb = mix(vec3(1.0, 0.62, 0.36), vec3(1.0), pow(mu, 0.6)) * mix(0.55, 1.0, pow(mu, 0.5));
    vec3 hot = col * limb * 900.0 * disc;

    // Glow as a camera sees it: hot inner bloom, warm halo, wide soft skirt.
    vec3 warm = col * vec3(1.0, 0.90, 0.76);
    float bloom = bell(a, radius * 2.0) * 1.8 + bell(a, radius * 5.5) * 0.30;
    float halo = 0.030 / (1.0 + pow(a / 0.06, 2.0)) + 0.006 / (1.0 + a * 12.0);

    // Faint six-point diffraction star, as from a camera aperture.
    vec2 uv = tangentUV(d, s);
    float ang = atan(uv.y, uv.x);
    float spikes = pow(abs(cos(ang * 3.0 + 0.4)), 220.0) + pow(abs(cos(ang * 3.0 + 0.4 + 0.5236)), 400.0) * 0.4;
    float starburst = spikes * exp(-a / (radius * 4.0)) * smoothstep(radius * 0.9, radius * 1.6, a) * 0.55;

    return hot + warm * (bloom * 2.6 + halo * 5.0 + starburst);
}

// ---- Craters for the moon -------------------------------------------------

float craterField(vec2 uv, float cells, float seed) {
    vec2 p = uv * cells;
    vec2 i = floor(p), f = fract(p);
    float h = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(float(x), float(y));
            vec2 id = i + g;
            float chance = hash12(id + seed);
            if (chance > 0.55) continue;
            vec2 o = hash22(id + seed * 1.7);
            float rad = mix(0.16, 0.42, hash12(id + seed + 5.1));
            float d = length(g + o - f) / rad;
            float bowl = -(1.0 - smoothstep(0.0, 0.92, d)) * 0.55;
            float rim = smoothstep(0.78, 0.98, d) * (1.0 - smoothstep(0.98, 1.22, d)) * 0.42;
            h += bowl + rim;
        }
    }
    return h;
}

float lunarHeight(vec2 uv) {
    return craterField(uv, 5.0, 1.0) * 0.9 + craterField(uv, 11.0, 7.0) * 0.55;
}

// Photographic moon: maria, craters with relief shading, phase terminator,
// earthshine on the dark limb and a faint halo. phase 0 = full.
vec3 moonDisc(vec3 sky, vec3 d, vec3 m, float radius, vec3 tint, float phase) {
    return sky; // отключено: на небе только атмосфера, облака и солнце
    float aa = pixelAngle(d);
    float dist = acos(clamp(dot(d, m), -1.0, 1.0));
    float halo = bell(dist, radius * 3.2) * 0.060 + bell(dist, radius * 9.0) * 0.016;
    sky += tint * halo;
    if (dist > radius * 1.2) return sky;

    float mask = 1.0 - smoothstep(radius - aa, radius + aa, dist);
    vec2 uv = tangentUV(d, m) / radius;
    float r2 = dot(uv, uv);
    float z = sqrt(max(1.0 - r2, 0.0));
    vec3 n = vec3(uv, z);

    float mare = fbm(vec3(uv * 1.5, 3.7), 4);
    float maria = smoothstep(0.50, 0.60, mare);
    float e = 0.02;
    float h0 = lunarHeight(uv);
    float hx = lunarHeight(uv + vec2(e, 0.0));
    float hy = lunarHeight(uv + vec2(0.0, e));
    vec3 bump = normalize(n + vec3(-(hx - h0), -(hy - h0), 0.0) / e * 0.09);

    vec3 L = normalize(vec3(sin(phase), 0.14, cos(phase)));
    float lit = clamp(dot(n, L), 0.0, 1.0);
    float shade = clamp(dot(bump, L), 0.0, 1.0);
    float albedo = mix(0.92, 0.40, maria) * (0.84 + 0.32 * fbm(vec3(uv * 9.0, 1.3), 3));
    float terminator = smoothstep(0.0, 0.10, lit);
    vec3 surf = tint * albedo * mix(lit, shade, 0.85) * terminator * 1.18;
    surf += tint * vec3(0.55, 0.65, 0.9) * albedo * 0.030 * (1.0 - terminator);
    return mix(sky, surf, mask) + tint * bell(dist, radius * 1.6) * 0.05 * terminator;
}

// ---------------------------------------------------------------------------
//  4. Volumetric clouds
//
//  A raymarched slab. Density is a rounded cumulus profile eroded by detail
//  noise; light is gathered with a short march towards the sun and a
//  multi-octave scatter approximation (silver lining, bright rims, deep dark
//  bases). Ambient comes from the sky above and the ground below. The layer
//  melts into the horizon through aerial perspective.
// ---------------------------------------------------------------------------

struct Cloud {
    float cover;    // 0..1 how much of the sky is filled
    float scale;    // spatial frequency
    float dens;     // optical thickness
    float bot;      // slab bottom (ray units, camera at 0)
    float top;      // slab top
    float seed;
    float wind;     // drift speed
    float erode;    // edge erosion strength 0..1
    float stratus;  // 0 = puffy cumulus, 1 = flat stratus
};

Cloud cloudCumulus(float cover, float seed) {
    return Cloud(cover, 1.5, 1.0, 1.1, 2.8, seed, 0.010, 0.75, 0.0);
}
Cloud cloudStratus(float cover, float seed) {
    return Cloud(cover, 1.2, 1.15, 1.3, 2.4, seed, 0.007, 0.55, 0.85);
}

// Clouds sit on a curved shell (planet radius in ray units), so the deck
// recedes to a real horizon: near clouds are big and detailed, far ones
// shrink, flatten and melt into the haze instead of smearing to infinity.
const float CLOUD_R = 70.0;

float cloudHeight(vec3 pos) {
    return length(pos + vec3(0.0, CLOUD_R, 0.0)) - CLOUD_R;
}

// Distance along d (from the camera at the origin) to the shell at altitude h.
float cloudShell(vec3 d, float h) {
    float b = d.y * CLOUD_R;
    float c = CLOUD_R * CLOUD_R - (CLOUD_R + h) * (CLOUD_R + h);
    return -b + sqrt(max(b * b - c, 0.0));
}

// Tileable 3D noise atlas (CloudNoiseTexture): 64 slices of 64x64 in an
// 8x8 grid, each slice padded by 1 px. R = Perlin-Worley, GBA = Worley at
// three frequencies. p is in texture periods (wraps).
uniform sampler2D CloudNoise;

vec4 cloudTex(vec3 p) {
    p = fract(p) * 64.0;
    float z = p.z - 0.5;
    float z0 = floor(z);
    float f = z - z0;
    float s0 = mod(z0 + 64.0, 64.0);
    float s1 = mod(z0 + 1.0, 64.0);
    vec2 a = (vec2(mod(s0, 8.0), floor(s0 / 8.0)) * 66.0 + 1.0 + p.xy) / 528.0;
    vec2 b = (vec2(mod(s1, 8.0), floor(s1 / 8.0)) * 66.0 + 1.0 + p.xy) / 528.0;
    return mix(textureLod(CloudNoise, a, 0.0), textureLod(CloudNoise, b, 0.0), f);
}

// Cloud density, Nubis-style: Perlin-Worley base shaped by a height profile,
// cut by coverage, then eroded by Worley detail at two scales. The finest
// scale is about a pixel wide at typical distances, which is what gives
// crisp, cauliflower edges instead of a soft blur. The whole field drifts
// with the wind; it does not boil, so clouds keep their shape.
// detail: 0 = shape only, 1 = + medium erosion, 2 = + fine erosion.
float cloudDensity(vec3 pos, Cloud c, int detail) {
    float h = (cloudHeight(pos) - c.bot) / (c.top - c.bot);
    if (h < 0.0 || h > 1.0) return 0.0;
    vec3 q = pos * c.scale * 0.55 + vec3(c.seed * 13.7, c.seed * 7.1, c.seed * 3.3);
    q.x += Time * c.wind;
    q.z += Time * c.wind * 0.45;

    // Weather: large-scale coverage map breaks the deck into fields and gaps.
    float weather = noise2(q.xz * 0.16 + c.seed * 3.1) * 0.7 + noise2(q.xz * 0.43 + c.seed) * 0.3;
    float cov = clamp(c.cover * 0.80 + (weather - 0.5) * 1.1, 0.0, 1.0);
    if (cov <= 0.0) return 0.0;

    vec4 n = cloudTex(q * vec3(0.26, 0.20, 0.26));
    // Smooth procedural noise hides the facets of the upscaled texture.
    n.r = mix(n.r, fbm(q * 1.3, 3), 0.30);
    float lowW = n.g * 0.625 + n.b * 0.25 + n.a * 0.125;
    float base = remap(n.r, lowW - 1.0, 1.0, 0.0, 1.0);

    // Height profile: flat base, rounded dome; stratus is a flat slab.
    float bottom = smoothstep(0.0, mix(0.08, 0.22, c.stratus), h);
    float top = 1.0 - smoothstep(mix(0.45, 0.80, c.stratus), 1.0, h);
    base *= bottom * top;

    float d = clamp(remap(base, 1.0 - cov * 0.72, 1.0, 0.0, 1.0), 0.0, 1.0) * mix(0.5, 1.0, cov);
    if (d > 0.0 && detail > 0) {
        // Wind shear: higher parts lean downwind.
        vec3 dq = q * 1.7 + vec3(h * 0.35, 0.0, h * 0.12);
        vec4 hn = cloudTex(dq);
        float hf = hn.g * 0.625 + hn.b * 0.25 + hn.a * 0.125;
        if (detail > 1) {
            vec4 fn = cloudTex(q * 5.5 + vec3(0.37, 0.11, 0.73));
            hf = hf * 0.62 + (fn.g * 0.7 + fn.b * 0.3) * 0.38;
        }
        // Billowy (inverted Worley) tops, wispy bottoms.
        float hfm = mix(1.0 - hf, hf, clamp(h * 5.0, 0.0, 1.0));
        d = clamp(remap(d, hfm * 0.45 * c.erode, 1.0, 0.0, 1.0), 0.0, 1.0);
    }
    return clamp(d * 3.5, 0.0, 1.0);
}

float cloudPhase(float mu) {
    float g1 = 0.80, g2 = -0.20, g3 = 0.30;
    float p1 = (1.0 - g1*g1) / pow(1.0 + g1*g1 - 2.0*g1*mu, 1.5);
    float p2 = (1.0 - g2*g2) / pow(1.0 + g2*g2 - 2.0*g2*mu, 1.5);
    float p3 = (1.0 - g3*g3) / pow(1.0 + g3*g3 - 2.0*g3*mu, 1.5);
    return (p1 * 0.25 + p2 * 0.25 + p3 * 0.50) / (4.0 * PI);
}

// sky: colour behind the clouds. sunDir/sunCol: light. ambTop/ambBot: ambient
// from above / below. haze: colour distant clouds melt into. flash: extra
// omnidirectional light (lightning).
vec4 cloudMarch(vec3 d, Cloud c, vec3 sunDir, vec3 sunCol,
                vec3 ambTop, vec3 ambBot, vec3 haze, float flash) {
    if (d.y <= -0.02 || c.cover <= 0.001) return vec4(0.0, 0.0, 0.0, 1.0);

    float t0 = cloudShell(d, c.bot);
    float t1 = min(cloudShell(d, c.top), t0 + 14.0);
    if (t1 <= t0 || t0 > 30.0) return vec4(0.0, 0.0, 0.0, 1.0);

    // Fine steps near the camera, coarser with distance.
    float span = t1 - t0;
    float dtNear = clamp(span / 48.0, 0.05, 0.20);
    float t = t0 + dtNear * hash12(gl_FragCoord.xy + vec2(c.seed * 31.7, 7.3));

    float mu = dot(d, sunDir);
    float phase = cloudPhase(mu);
    float sunUp = smoothstep(-0.10, 0.08, sunDir.y);

    float T = 1.0;
    vec3 acc = vec3(0.0);
    bool inside = false;

    for (int i = 0; i < 72; i++) {
        if (t > t1 || T < 0.02) break;
        float dt = dtNear * (1.0 + (t - t0) * 0.10);
        vec3 pos = d * t;
        // Cheap probe with the shape only; full detail once inside.
        if (!inside) {
            if (cloudDensity(pos, c, 0) <= 0.0) { t += dt * 2.0; continue; }
            inside = true;
            t = max(t - dt * 1.5, t0);
            continue;
        }
        int lod = t < 4.0 ? 2 : 1;
        float dens = cloudDensity(pos, c, lod);
        if (dens > 0.004) {
            float h = clamp((cloudHeight(pos) - c.bot) / (c.top - c.bot), 0.0, 1.0);
            // Light march: six taps towards the sun, exponentially spaced.
            float ld = cloudDensity(pos + sunDir * 0.08, c, 1) * 0.12
                     + cloudDensity(pos + sunDir * 0.30, c, 0) * 0.30
                     + cloudDensity(pos + sunDir * 0.75, c, 0) * 0.55
                     + cloudDensity(pos + sunDir * 1.60, c, 0) * 0.83;
            float od = ld * c.dens * 9.0;
            // Multi-scatter octaves (Wrenninge): each lets more light through
            // with a wider lobe — bright silver edges, glowing interiors.
            float E = exp(-od) * phase * 4.0 * PI * 0.65
                    + exp(-od * 0.35) * (phase * 4.0 * PI * 0.5 + 0.5) * 0.18
                    + exp(-od * 0.12) * 0.05;
            float powder = 1.0 - exp(-dens * c.dens * 5.0);
            vec3 direct = sunCol * E * mix(1.0, powder, 0.45 + 0.4 * max(-mu, 0.0)) * sunUp;
            // Ambient: sky above, bounce below, occluded by the cloud above.
            float occl = exp(-ld * c.dens * 1.5);
            // Bases see the whole sky dome plus ground bounce: never darker
            // than about half the top ambient (blue-grey bases, as in photos).
            vec3 ambB = max(ambBot, ambTop * 0.55);
            vec3 amb = mix(ambB, ambTop, smoothstep(0.0, 0.9, h)) * (0.55 + 0.45 * h) * (0.45 + 0.55 * occl);
            vec3 lc = direct + amb + vec3(flash) * (0.6 + 0.4 * h);

            float a = 1.0 - exp(-dens * dt * c.dens * 12.0);
            float aerial = 1.0 - exp(-t * 0.040);
            lc = mix(lc, haze, clamp(aerial * aerial, 0.0, 0.80));
            acc += T * a * lc;
            T *= 1.0 - a;
        }
        t += dt;
    }

    float fade = smoothstep(-0.02, 0.05, d.y);
    return vec4(acc * fade, mix(1.0, T, fade));
}

// Bicubic (B-spline, 4 bilinear taps) read of the low-res cloud buffer:
// smooth upscale without blocky edges.
vec4 cloudFetch() {
    vec2 size = vec2(textureSize(CloudBuffer, 0));
    vec2 uv = gl_FragCoord.xy / max(Resolution, vec2(1.0)) * size - 0.5;
    vec2 f = fract(uv);
    uv -= f;
    vec4 n = vec4(1.0, 2.0, 3.0, 4.0);
    vec4 nx = n - f.x, ny = n - f.y;
    vec4 sx = nx * nx * nx, sy = ny * ny * ny;
    vec4 wx = vec4(sx.x, sx.y - 4.0 * sx.x, sx.z - 4.0 * sx.y + 6.0 * sx.x, 0.0);
    wx.w = 6.0 - wx.x - wx.y - wx.z;
    vec4 wy = vec4(sy.x, sy.y - 4.0 * sy.x, sy.z - 4.0 * sy.y + 6.0 * sy.x, 0.0);
    wy.w = 6.0 - wy.x - wy.y - wy.z;
    wx /= 6.0; wy /= 6.0;
    vec4 cc = uv.xxyy + vec2(-0.5, 1.5).xyxy;
    vec4 ss = vec4(wx.xz + wx.yw, wy.xz + wy.yw);
    vec4 off = (cc + vec4(wx.yw, wy.yw) / ss) / size.xxyy;
    vec4 a0 = textureLod(CloudBuffer, off.xz, 0.0);
    vec4 a1 = textureLod(CloudBuffer, off.yz, 0.0);
    vec4 a2 = textureLod(CloudBuffer, off.xw, 0.0);
    vec4 a3 = textureLod(CloudBuffer, off.yw, 0.0);
    float kx = ss.x / (ss.x + ss.y);
    float ky = ss.z / (ss.z + ss.w);
    return mix(mix(a3, a2, kx), mix(a1, a0, kx), ky);
}

// All cloud layers of a mode, folded together: col' = col * T + A.
float gCloudT = 1.0;
vec3 gCloudA = vec3(0.0);
bool gCloudDone = false;

vec3 cloudVolume(vec3 sky, vec3 d, Cloud c, vec3 sunDir, vec3 sunCol,
                 vec3 ambTop, vec3 ambBot, vec3 haze, float flash) {
    if (SkyPass > 1.5) {
        // Full-res sky: every layer was already marched at low res.
        if (gCloudDone) return sky;
        gCloudDone = true;
        vec4 cb = cloudFetch();
        return sky * cb.a + cb.rgb;
    }
    vec4 r = cloudMarch(d, c, sunDir, sunCol, ambTop, ambBot, haze, flash);
    gCloudA = gCloudA * r.a + r.rgb;
    gCloudT *= r.a;
    return sky * r.a + r.rgb;
}

// High, thin, stretched cloud veil (cirrus / altostratus), lit from below by
// the low sun. Two-tap shading gives it filament structure.
vec3 cirrusVeil(vec3 sky, vec3 d, vec3 sunDir, vec3 litCol, vec3 baseCol, float amount, float seed) {
    if (d.y <= 0.0) return sky;
    vec3 pp = d * cloudShell(d, 7.0);
    vec3 q = vec3(pp.x, 0.0, pp.z) * 0.11 + vec3(Time * 0.004, seed, Time * 0.0015);
    q += vec3(fbm(q * 0.7, 2) - 0.5) * 1.3;
    float n = fbm(q * vec3(1.0, 3.0, 3.6), 5);
    float streak = fbm(q * vec3(0.6, 1.0, 9.0) + 2.0, 3);
    n = n * 0.75 + streak * 0.25;
    float veil = smoothstep(0.50, 0.76, n) * smoothstep(0.0, 0.14, d.y);
    float n2 = fbm((q + sunDir * 0.12) * vec3(1.0, 3.0, 3.6), 3);
    float lit = clamp(0.55 + (n - n2) * 5.0, 0.0, 1.0);
    float mu = max(dot(d, sunDir), 0.0);
    vec3 col = mix(baseCol, litCol, lit) * (1.0 + pow(mu, 8.0) * 1.5);
    return mix(sky, col, clamp(veil * amount, 0.0, 1.0));
}

// Crepuscular rays: sample the cloud deck along the line to the sun and
// accumulate what escapes. Cheap and tied to the real cloud shapes.
float cloudShafts(vec3 d, vec3 sunDir, Cloud c) {
    float mu = dot(d, sunDir);
    if (mu < 0.2) return 0.0;
    // Full-res sky pass: the rays become a soft forward glow; marching the
    // deck per full-res pixel costs more than the whole cloud pass.
    if (SkyPass > 1.5) return 0.55;
    float acc = 0.0;
    float j = ign(gl_FragCoord.xy, 3.0);
    for (int i = 0; i < 6; i++) {
        float f = (float(i) + j) / 6.0;
        vec3 dir = normalize(mix(d, sunDir, f * 0.92));
        float occ = 0.0;
        if (dir.y > 0.0) {
            vec3 pos = dir * cloudShell(dir, c.bot + (c.top - c.bot) * 0.45);
            occ = cloudDensity(pos, c, 0);
        }
        acc += 1.0 - occ;
    }
    return acc / 6.0;
}

// ---------------------------------------------------------------------------
//  5. Night sky
// ---------------------------------------------------------------------------

// One layer of stars: a thin shell of random points in a 3D cell grid, so
// the field is seamless on the whole sphere. Brightness follows a steep
// magnitude law (many faint, few bright); the brightest get a soft glare.
vec3 starLayer(vec3 d, float scale, float seed, float bright) {
    float pa = pixelAngle(d);
    // A star wider than ~0.07 cell would be clipped by its cell border (blocky
    // blobs), so the grid never gets finer than the pixel grid allows.
    scale = min(scale, 0.105 / pa);
    float px = pa * scale;
    vec3 p = d * scale;
    vec3 c = floor(p);
    vec3 h = hash33(c + seed);
    vec3 pos = c + 0.22 + 0.56 * hash33(c * 1.37 + seed + 4.1);
    vec3 q = p - pos;
    float radial = dot(q, d);
    vec3 lat = q - d * radial;
    float shell = 1.0 - smoothstep(0.14, 0.24, abs(radial));

    float mag = pow(h.y, 16.0);
    float s0 = mix(0.040, 0.070, mag);
    float r = max(s0, px * 0.62);
    float core = exp(-dot(lat, lat) / (r * r));
    // Energy conservation: sub-pixel stars dim instead of aliasing.
    float cover = clamp((s0 * s0) / (r * r), 0.0, 1.0);
    float rg = min(r * 3.0, 0.11);
    float glare = exp(-dot(lat, lat) / (rg * rg)) * mag * 0.45;

    vec3 tint = mix(vec3(0.62, 0.76, 1.00), vec3(1.00, 0.78, 0.55), smoothstep(0.15, 0.95, h.z));
    tint = mix(tint, vec3(1.0), 0.45);
    float twinkle = 0.84 + 0.16 * sin(Time * (0.8 + h.x * 2.4) + h.z * 60.0);
    float b = (0.14 + 7.0 * mag) * bright * twinkle;
    return tint * (core * cover + glare) * b * shell * step(h.x, 0.24);
}

vec3 stars(vec3 d, float strength) {
    return vec3(0.0); // отключено: на небе только атмосфера, облака и солнце
    vec3 c = starLayer(d, 64.0, 11.0, 1.00) + starLayer(d, 110.0, 29.0, 0.80) + starLayer(d, 190.0, 53.0, 0.70);
    return c * strength * smoothstep(-0.03, 0.06, d.y);
}

// Milky Way: a luminous band with a warm bulge, a long dark rift, soft dust
// clouds and a mottled grain of unresolved stars. axis = galactic pole.
vec3 milkyWay(vec3 d, vec3 axis, float strength) {
    return vec3(0.0); // отключено: на небе только атмосфера, облака и солнце
    float pa = pixelAngle(d);
    vec3 n = normalize(axis);
    vec3 cdir = normalize(cross(n, vec3(0.31, 0.93, 0.18)));
    float lat = dot(d, n);
    float toCore = max(dot(d, cdir), 0.0);

    // Noise domain stretched along the band: structures run lengthwise.
    vec3 ps = (d - n * lat) * 3.0 + n * lat * 11.0;
    float bandNoise = fbm(ps * 0.9, 4);
    float mid = fbm(ps * 2.1 + 7.0, 4);
    float gs = min(260.0, 0.45 / pa);
    float grain = 0.55 * noise3(d * gs) + 0.45 * noise3(d * gs * 2.3 + 5.0);

    float width = 0.14 + 0.07 * pow(toCore, 2.0);
    float band = exp(-pow(abs(lat + (bandNoise - 0.5) * 0.10) / width, 1.6));
    float bulge = pow(toCore, 6.0) * exp(-pow(abs(lat) / 0.28, 1.3));
    float halo = exp(-abs(lat) / 0.55) * 0.08;

    // Dark rift down the middle plus soft dust clouds.
    float rift = bell(lat - 0.012 + (bandNoise - 0.5) * 0.08, 0.030);
    float lumps = smoothstep(0.52, 0.78, fbm(ps * 2.6 + 3.0, 4)) * 0.55;
    float dust = clamp(rift * 0.80 + lumps, 0.0, 1.0) * smoothstep(0.03, 0.5, band);

    float speckle = smoothstep(0.55, 0.85, grain);
    float body = (band * (0.40 + 0.80 * mid) + bulge * 1.5 + halo) * (0.55 + 0.9 * speckle);
    vec3 cool = vec3(0.64, 0.74, 1.00);
    vec3 warm = vec3(1.00, 0.86, 0.66);
    vec3 col = mix(cool, warm, clamp(bulge * 1.8 + toCore * 0.25, 0.0, 1.0));
    float hii = smoothstep(0.72, 0.9, fbm(ps * 3.4 + 11.0, 3)) * band;
    col = mix(col, vec3(1.0, 0.50, 0.66), hii * 0.30);

    vec3 glow = col * body * (1.0 - dust * 0.74);
    return glow * strength * 0.26 * smoothstep(-0.03, 0.10, d.y);
}

// Emission nebula: domain-warped gas, dust pillars, three-colour ramp.
vec3 nebula(vec3 d, vec3 seedPos, vec3 colA, vec3 colB, vec3 colC, float strength) {
    return vec3(0.0); // отключено: на небе только атмосфера, облака и солнце
    vec3 p = d * 2.2 + seedPos;
    vec3 w1 = vec3(fbm(p * 1.3 + vec3(Time * 0.0025), 4) - 0.5) * 2.2;
    vec3 w2 = vec3(fbm(p * 2.4 + w1 + 7.3, 4) - 0.5) * 1.4;
    float n = fbm(p + w1 + w2, 5);
    float gas = smoothstep(0.38, 0.74, n);
    float dust = smoothstep(0.55, 0.78, fbm(p * 1.8 + w2 * 1.5 + 3.7, 4));
    float t = fbm(p * 0.9 + w1, 3);
    vec3 col = mix(colA, colB, smoothstep(0.28, 0.72, t));
    col = mix(col, colC, smoothstep(0.58, 0.88, n));
    float edge = smoothstep(0.38, 0.52, n) * (1.0 - smoothstep(0.52, 0.74, n));
    col *= 1.0 - dust * 0.74;
    col += colC * edge * 0.18;
    return col * gas * (0.50 + 0.50 * smoothstep(0.50, 0.84, n)) * strength * smoothstep(-0.05, 0.16, d.y);
}

// Spiral galaxy for scale: bright nucleus, two arms, dust, knots.
vec3 spiralGalaxy(vec3 d, vec3 center, float size, vec3 coreCol, vec3 armCol) {
    return vec3(0.0); // отключено: на небе только атмосфера, облака и солнце
    float dist = acos(clamp(dot(d, center), -1.0, 1.0));
    float outer = 1.0 - smoothstep(size * 2.0, size * 4.5, dist);
    if (outer <= 0.001) return vec3(0.0);
    vec2 uv = tangentUV(d, center) / size;
    float ca = cos(0.55), sa = sin(0.55);
    uv = vec2(ca * uv.x - sa * uv.y, sa * uv.x + ca * uv.y);
    uv.y /= 0.42;
    float r = length(uv);
    float a = atan(uv.y, uv.x);
    float spiral = fract((a - log(max(r, 0.01)) * 1.6) / PI);
    float arm = bell(spiral - 0.45, 0.20);
    float nucleus = bell(r, 0.18) * 3.4 + bell(r, 0.55) * 1.0;
    float armGlow = arm * smoothstep(0.10, 0.6, r) * exp(-r * 0.55);
    float dark = bell(spiral - 0.27, 0.08) * smoothstep(0.25, 0.85, r);
    float knots = smoothstep(0.72, 0.94, noise3(vec3(uv * 8.0, 3.3))) * armGlow * 1.6;
    vec3 col = coreCol * nucleus + armCol * armGlow * 1.5 + vec3(0.72, 0.82, 1.0) * knots;
    col *= 1.0 - 0.55 * dark;
    return col * outer * smoothstep(-0.02, 0.2, d.y);
}

// Aurora: vertical curtains seen through a marched altitude slab. Each
// curtain is a folded ribbon on the ground plane; emission hangs from a
// bright, sharp lower edge in fine parallel rays and fades upward. Colour:
// magenta fringe -> green body -> violet top.
vec3 aurora(vec3 d, float strength, float seed) {
    return vec3(0.0); // отключено: на небе только атмосфера, облака и солнце
    if (d.y < 0.02) return vec3(0.0);
    vec3 acc = vec3(0.0);
    float jitter = ign(gl_FragCoord.xy, seed);
    const int N = 40;
    float t = Time * 0.05;
    for (int i = 0; i < N; i++) {
        float f = (float(i) + jitter) / float(N);
        float alt = mix(0.50, 1.90, f);
        vec3 pos = d * (alt / d.y);
        vec2 g = pos.xz * 0.30;

        float fold = sin(g.x * 1.1 + t * 1.2 + seed) * 0.60 + sin(g.x * 2.7 - t * 0.8 + seed * 2.0) * 0.22
                   + (fbm(vec2(g.x * 0.8, t * 0.35 + seed), 3) - 0.5) * 1.8;
        float u1 = g.y - fold;
        float u2 = g.y - fold * 0.7 - 1.9 - 0.5 * sin(g.x * 0.7 + t * 0.6);
        // The curtain is a thin sheet: sharper than a cloud, wider at the base.
        float w = mix(0.11, 0.035, f);
        float ribbon = exp(-u1 * u1 / (w * w)) + 0.45 * exp(-u2 * u2 / (w * w * 1.3));

        // Rays: bright thin streaks that slide along the curtain.
        float lane = g.x * 9.0 + sin(g.x * 1.3 + seed) * 2.0 - t * 2.5;
        float rays = smoothstep(0.25, 0.85, fbm(vec2(lane, g.y * 0.6 + seed), 3));
        rays *= 0.55 + 0.45 * sin(g.x * 41.0 + fbm(vec2(g.x * 3.0, g.y), 2) * 6.0);
        rays = 0.25 + 0.75 * rays;

        float vert = smoothstep(0.0, 0.035, f) * exp(-f * 2.4);
        float e = ribbon * rays * vert;

        vec3 low = vec3(1.00, 0.18, 0.55);
        vec3 mid = vec3(0.12, 1.00, 0.42);
        vec3 high = vec3(0.50, 0.28, 0.95);
        vec3 c = mix(low, mid, smoothstep(0.0, 0.10, f));
        c = mix(c, high, smoothstep(0.50, 1.0, f));
        acc += c * e * (1.0 / float(N)) * 5.5;
    }
    float horizon = smoothstep(0.02, 0.18, d.y);
    return acc * strength * horizon;
}

// Meteor: tapered streak with hot head and a lingering ionised train.
vec3 meteor(vec3 d, float seed, float period, vec3 tint) {
    float phase = fract(Time / period + seed * 0.16);
    float life = smoothstep(0.0, 0.03, phase) * (1.0 - smoothstep(0.24, 0.34, phase));
    vec3 center = worldDir(seed * 71.0, 30.0 + 14.0 * sin(seed * 3.0));
    vec2 p = tangentUV(d, center);
    vec2 head = vec2(phase * 1.9 - 0.30, 0.12 - phase * 0.52);
    vec2 axis = normalize(vec2(1.0, -0.30));
    float along = clamp(dot(head - p, axis), 0.0, 0.30);
    float dist = length(p - head + axis * along);
    float width = max(0.0022, pixelAngle(d) * 1.6);
    float taper = 1.0 - along / 0.30;
    float trail = bell(dist, width * (0.6 + 1.6 * taper)) * taper * taper;
    float headGlow = bell(length(p - head), width * 3.2) * 1.4;
    float train = bell(dist, width * 7.0) * taper
                * smoothstep(0.03, 0.12, phase) * (1.0 - smoothstep(0.45, 0.95, phase)) * 0.26;
    float vis = smoothstep(0.0, 0.2, dot(d, center)) * smoothstep(0.0, 0.14, d.y);
    return ((tint * trail * 2.6 + vec3(1.0, 0.95, 0.88) * headGlow) * life + tint * train) * vis;
}

vec3 meteors(vec3 d, float seed, vec3 tint) {
    return vec3(0.0); // отключено: на небе только атмосфера, облака и солнце
    vec3 acc = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        acc += meteor(d, seed + fi * 13.7, 7.0 + fi * 2.6, tint);
    }
    return acc;
}

// Faint permanent night glow: airglow band near the horizon.
vec3 airglow(vec3 d, vec3 tint, float strength) {
    float h = max(d.y, 0.0);
    return tint * strength * (exp(-h * 9.0) * 0.8 + exp(-h * 2.4) * 0.25) * (0.7 + 0.3 * fbm(d * 5.0 + vec3(Time * 0.01), 3));
}

// ---------------------------------------------------------------------------
//  6. Particles, rainbow, lightning
// ---------------------------------------------------------------------------

// Drifting particles (snow, petals, embers, dust): points of a 3D grid on a
// shell of the given radius that slides with the wind. soft > 0 blurs them
// (out-of-focus foreground flakes).
float particleShell(vec3 d, float radius, float cell, float size, vec3 vel, float seed, float soft) {
    return 0.0; // отключено: на небе только атмосфера, облака и солнце
    vec3 p = d * radius + vel * Time;
    vec3 c = floor(p / cell);
    vec3 h = hash33(c + seed);
    vec3 pos = (c + 0.2 + 0.6 * hash33(c + seed + 9.1)) * cell;
    pos.xz += vec2(sin(Time * (0.7 + h.x) + h.y * 40.0), cos(Time * (0.6 + h.y) + h.z * 40.0)) * cell * 0.12;
    vec3 q = p - pos;
    float radial = dot(q, d);
    vec3 lat = q - d * radial;
    float shell = 1.0 - smoothstep(cell * 0.10, cell * 0.22, abs(radial));
    float s = size * mix(0.6, 1.3, h.z);
    float r = max(s + soft, pixelAngle(d) * radius * 0.7);
    float v = exp(-dot(lat, lat) / (r * r));
    float cover = clamp((s + soft) * (s + soft) / (r * r), 0.0, 1.0);
    return v * cover * shell * step(h.x, 0.55) * mix(0.55, 1.0, h.y);
}

// Rainbow: primary at 42 deg from the antisolar point (red outside),
// secondary at 51 deg (reversed, fainter).
vec3 rainbow(vec3 d, vec3 sunDir, float strength) {
    return vec3(0.0); // отключено: на небе только атмосфера, облака и солнце
    float ang = degrees(acos(clamp(dot(d, -sunDir), -1.0, 1.0)));
    float s1 = (ang - 40.3) / 2.5;
    float edgeP = smoothstep(0.0, 0.10, s1) * (1.0 - smoothstep(0.90, 1.0, s1));
    vec3 prim = spectral(0.86 - clamp(s1, 0.0, 1.0) * 0.86);
    float s2 = (ang - 50.4) / 3.6;
    float edgeS = smoothstep(0.0, 0.10, s2) * (1.0 - smoothstep(0.90, 1.0, s2));
    vec3 sec = spectral(clamp(s2, 0.0, 1.0) * 0.86);
    float visible = smoothstep(-0.02, 0.06, d.y);
    return (prim * edgeP * 0.50 + sec * edgeS * 0.18) * strength * visible;
}

// Lightning bolt with a branch, as a thin additive glow. Writes the global
// flash level (for lighting the clouds).
vec3 lightning(vec3 d, float seed, out float flash) {
    float period = 5.3 + hash11(seed) * 3.0;
    float cycle = floor(Time / period + seed * 0.7);
    float ph = fract(Time / period + seed * 0.7) * period;
    float fire = step(0.35, hash11(cycle * 3.7 + seed));
    float f1 = exp(-ph * 14.0);
    float f2 = exp(-abs(ph - 0.22) * 18.0) * 0.7;
    float e = (f1 + f2) * fire;
    flash = e * 0.9;
    if (e < 0.02) return vec3(0.0);

    float az = hash11(cycle * 9.1 + seed) * 360.0;
    vec3 cdir = worldDir(az, 40.0);
    if (dot(d, cdir) < 0.2) return vec3(0.0);
    vec2 uv = tangentUV(d, cdir);
    float v = clamp((0.55 - uv.y) / 0.90, 0.0, 1.0);
    float x0 = (hash11(cycle * 4.3) - 0.5) * 0.4;
    float jag = (fbm(vec2(v * 9.0, cycle), 3) - 0.5) * 0.30 + (noise2(vec2(v * 31.0, cycle)) - 0.5) * 0.05;
    float u = uv.x - (x0 + v * 0.12 + jag);
    float w = max(0.0028, pixelAngle(d) * 1.5);
    float bolt = bell(u, w) * 2.4 + bell(u, w * 6.0) * 0.5;
    float vb = clamp((0.30 - uv.y) / 0.60, 0.0, 1.0);
    float jb = (fbm(vec2(vb * 8.0, cycle + 4.0), 3) - 0.5) * 0.24;
    float ub = uv.x - (x0 + 0.04 + vb * 0.20 + jb);
    bolt += bell(ub, w) * 1.2 * step(uv.y, 0.30);
    return vec3(0.0) * bolt * e * smoothstep(0.0, 0.1, v) * step(v, 0.999);
}

// ---------------------------------------------------------------------------
//  7. Grading and the final composite
// ---------------------------------------------------------------------------

// Saturation + exposure (stops).
vec3 grade(vec3 c, float sat, float exposure) {
    c *= exp2(exposure);
    return mix(vec3(luma(c)), c, sat);
}

// ACES filmic (Narkowicz fit) on a scalar.
float acesCurve(float x) {
    const float a = 2.51, b = 0.03, cc = 2.43, dd = 0.59, e = 0.14;
    return clamp((x * (a * x + b)) / (x * (cc * x + dd) + e), 0.0, 1.0);
}

// Hue-preserving filmic curve: the curve acts on the brightest channel, so
// a sunset stays orange instead of sliding to yellow; only genuinely
// over-exposed pixels (the sun, lightning) bleach to white.
vec3 tonemapSky(vec3 c) {
    c = max(c, vec3(0.0));
    float m = max(max(c.r, c.g), c.b);
    float mapped = acesCurve(m);
    vec3 kept = c * (mapped / max(m, 0.0001));
    return mix(kept, vec3(mapped), smoothstep(1.2, 9.0, m) * 0.9);
}

// Final composite: exposure, filmic curve, sRGB encode, vignette, dither,
// fog blend towards the world fog colour near the horizon.
vec3 finishSky(vec3 c, vec3 d);

vec4 skyOutput(vec3 col, vec3 d) {
    if (SkyPass > 0.5 && SkyPass < 1.5) return vec4(gCloudA, gCloudT);
    return vec4(finishSky(col, d), 1.0);
}

vec3 finishSky(vec3 c, vec3 d) {
    c = max(c, vec3(0.0)) * Intensity;
    c = tonemapSky(c);
    c = pow(c, vec3(1.0 / 2.2));
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.18);

    vec2 q = gl_FragCoord.xy / max(Resolution, vec2(1.0)) * 2.0 - 1.0;
    c *= clamp(1.0 - dot(q * 0.5, q * 0.5) * 0.12, 0.0, 1.0);

    c += (hash12(gl_FragCoord.xy) + hash12(gl_FragCoord.xy + 71.3) - 1.0) / 255.0;

    float fog = (1.0 - smoothstep(0.015, 0.26, max(d.y, 0.0))) * FogBlend;
    return mix(c, FogColor, clamp(fog, 0.0, 1.0));
}
