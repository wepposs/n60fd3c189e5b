package vesence.renderengine.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Тайлящийся 3D-шум для объёмных облаков (подход Horizon Zero Dawn / Nubis),
 * уложенный в 2D-атлас: 64 среза 64x64 сеткой 8x8, у каждого среза рамка в
 * 1 px с заворотом, чтобы билинейная выборка не протекала в соседний тайл.
 *
 * <p>R — Perlin-Worley (основа формы: пухлые клубы с мягкими переходами),
 * G/B/A — Worley на 3 частотах (эрозия краёв, «цветная капуста»).
 * Генерируется один раз при первом использовании (~0.3 с).
 */
@Environment(EnvType.CLIENT)
public final class CloudNoiseTexture {
    public static final int N = 64;
    public static final int TILES = 8;
    public static final int TILE = N + 2;
    public static final int SIZE = TILE * TILES; // 528

    private static GpuTexture texture;
    private static GpuTextureView view;

    private CloudNoiseTexture() {}

    public static GpuTextureView view() {
        if (view == null) create();
        return view;
    }

    private static void create() {
        float[][] ch = generate();
        NativeImage img = new NativeImage(SIZE, SIZE, false);
        for (int z = 0; z < N; z++) {
            int ox = (z % TILES) * TILE, oy = (z / TILES) * TILE;
            for (int y = -1; y <= N; y++) {
                for (int x = -1; x <= N; x++) {
                    int i = idx(Math.floorMod(x, N), Math.floorMod(y, N), z);
                    int r = to8(ch[0][i]), g = to8(ch[1][i]), b = to8(ch[2][i]), a = to8(ch[3][i]);
                    img.setPixelABGR(ox + 1 + x, oy + 1 + y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
        }
        texture = RenderSystem.getDevice().createTexture(() -> "vesence:cloud_noise",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, img);
        img.close();
        view = RenderSystem.getDevice().createTextureView(texture);
    }

    public static void close() {
        if (view != null) { view.close(); view = null; }
        if (texture != null) { texture.close(); texture = null; }
    }

    private static int idx(int x, int y, int z) { return (z * N + y) * N + x; }

    private static int to8(float v) { return Math.max(0, Math.min(255, Math.round(v * 255f))); }

    // ---------------------------------------------------------------- noise

    private static float[][] generate() {
        float[][] out = new float[4][N * N * N];
        float[] w4 = worleyGrid(4, 11), w8 = worleyGrid(8, 23), w16 = worleyGrid(16, 37), w32 = worleyGrid(32, 51);
        for (int z = 0; z < N; z++) {
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    float u = (x + 0.5f) / N, v = (y + 0.5f) / N, w = (z + 0.5f) / N;
                    // Перлин-fbm, периодичный по кубу.
                    float p = 0f, amp = 1f, norm = 0f;
                    for (int o = 0; o < 5; o++) {
                        int per = 4 << o;
                        p += amp * perlin(u * per, v * per, w * per, per);
                        norm += amp;
                        amp *= 0.5f;
                    }
                    p = p / norm * 0.5f + 0.5f;
                    float wa = worley(w4, 4, u, v, w), wb = worley(w8, 8, u, v, w), wc = worley(w16, 16, u, v, w), wd = worley(w32, 32, u, v, w);
                    float wfbm = wa * 0.625f + wb * 0.25f + wc * 0.125f;
                    // Perlin-Worley: Перлин, «раздутый» инвертированным Worley.
                    float pw = remap(p, wfbm - 1f, 1f, 0f, 1f);
                    int i = idx(x, y, z);
                    out[0][i] = clamp01(pw);
                    out[1][i] = wb * 0.625f + wc * 0.25f + wd * 0.125f;
                    out[2][i] = wc * 0.625f + wd * 0.25f + wa * 0.125f;
                    out[3][i] = wd * 0.75f + wb * 0.25f;
                }
            }
        }
        return out;
    }

    private static float remap(float x, float a, float b, float c, float d) {
        return c + (x - a) / (b - a) * (d - c);
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    /** Случайные точки в ячейках периодической сетки cells^3. */
    private static float[] worleyGrid(int cells, int seed) {
        float[] pts = new float[cells * cells * cells * 3];
        long s = seed * 0x9E3779B97F4A7C15L;
        for (int i = 0; i < pts.length; i++) {
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            pts[i] = ((s >>> 11) & 0xFFFFFF) / (float) 0x1000000;
        }
        return pts;
    }

    /** Инвертированный Worley F1 (1 в центре клеток-клубов, 0 на границах). */
    private static float worley(float[] pts, int cells, float u, float v, float w) {
        float px = u * cells, py = v * cells, pz = w * cells;
        int ix = (int) Math.floor(px), iy = (int) Math.floor(py), iz = (int) Math.floor(pz);
        float best = 9f;
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int cx = ix + dx, cy = iy + dy, cz = iz + dz;
            int k = ((Math.floorMod(cz, cells) * cells + Math.floorMod(cy, cells)) * cells + Math.floorMod(cx, cells)) * 3;
            float qx = cx + pts[k] - px, qy = cy + pts[k + 1] - py, qz = cz + pts[k + 2] - pz;
            float d = qx * qx + qy * qy + qz * qz;
            if (d < best) best = d;
        }
        return clamp01(1f - (float) Math.sqrt(best));
    }

    private static float fade(float t) { return t * t * t * (t * (t * 6f - 15f) + 10f); }

    private static float grad(int h, float x, float y, float z) {
        switch (h & 15) {
            case 0: return x + y; case 1: return -x + y; case 2: return x - y; case 3: return -x - y;
            case 4: return x + z; case 5: return -x + z; case 6: return x - z; case 7: return -x - z;
            case 8: return y + z; case 9: return -y + z; case 10: return y - z; case 11: return -y - z;
            case 12: return x + y; case 13: return -y + z; case 14: return -x + y; default: return -y - z;
        }
    }

    private static int hash(int x, int y, int z, int per) {
        x = Math.floorMod(x, per); y = Math.floorMod(y, per); z = Math.floorMod(z, per);
        int h = x * 374761393 + y * 668265263 + z * 1274126177;
        h = (h ^ (h >>> 13)) * 1103515245;
        return h ^ (h >>> 16);
    }

    /** Периодический градиентный шум Перлина, примерно -1..1. */
    private static float perlin(float x, float y, float z, int per) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
        float fx = x - x0, fy = y - y0, fz = z - z0;
        float u = fade(fx), v = fade(fy), w = fade(fz);
        float n000 = grad(hash(x0, y0, z0, per), fx, fy, fz);
        float n100 = grad(hash(x0 + 1, y0, z0, per), fx - 1, fy, fz);
        float n010 = grad(hash(x0, y0 + 1, z0, per), fx, fy - 1, fz);
        float n110 = grad(hash(x0 + 1, y0 + 1, z0, per), fx - 1, fy - 1, fz);
        float n001 = grad(hash(x0, y0, z0 + 1, per), fx, fy, fz - 1);
        float n101 = grad(hash(x0 + 1, y0, z0 + 1, per), fx - 1, fy, fz - 1);
        float n011 = grad(hash(x0, y0 + 1, z0 + 1, per), fx, fy - 1, fz - 1);
        float n111 = grad(hash(x0 + 1, y0 + 1, z0 + 1, per), fx - 1, fy - 1, fz - 1);
        float a = lerp(lerp(n000, n100, u), lerp(n010, n110, u), v);
        float b = lerp(lerp(n001, n101, u), lerp(n011, n111, u), v);
        return lerp(a, b, w);
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
