package vesence.utils.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import vesence.mixin.AbstractTextureAccessor;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * Анимированный плащ: на внешней стороне плаща крутится тот же логотип, что и в главном меню.
 *
 * <h2>Как это устроено</h2>
 * <ul>
 *   <li>Берётся обычная {@code cape.png}, уменьшенная вдвое (2048x1024 → 1024x512).
 *       Внешняя панель плаща (в UV: 10x16 «пикселей» со сдвигом 1,1) заливается
 *       гладким тёмным градиентом — он снят с самой картинки, так что статичный логотип
 *       под анимацией не просвечивает.</li>
 *   <li>В центр панели каждый кадр накладывается кадр логотипа (режим «экран»:
 *       чёрный фон кадра пропадает, остаётся только свечение). Края кадра плавно гаснут.</li>
 *   <li>Кадры логотипа лежат в ресурсах как PNG (250 шт., 50 fps). Берём каждый второй —
 *       получается 25 fps при длине цикла те же 5 секунд.</li>
 *   <li>Динамическая текстура одна, с постоянным id: меняются только пиксели логотипа
 *       (~20 тыс. пикселей на кадр), затем текстура целиком заливается в GPU (2 МБ).</li>
 *   <li>Декод PNG и подготовка кадров идут в фоне; пока не готово, {@link #current()}
 *       возвращает {@code null}, и модуль показывает обычный плащ.</li>
 * </ul>
 */
public final class AnimatedLogoCapeTexture {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("vesence", "cape/logo");
    private static final String CAPE_SOURCE = "assets/vesence/textures/cape/cape.png";
    private static final String FRAME_PATTERN = "assets/vesence/videos/logo/f%04d.png";

    private static final int SOURCE_FRAMES = 250;
    private static final int FRAME_STEP = 2;
    private static final int FRAMES = SOURCE_FRAMES / FRAME_STEP;
    /** 50 fps исходника / 2 = 25 fps. */
    private static final long FRAME_MS = 40L;

    // Внешняя панель плаща в координатах уменьшенной вдвое текстуры.
    private static final int PANEL_X = 16;
    private static final int PANEL_Y = 16;
    private static final int PANEL_W = 160;
    private static final int PANEL_H = 256;

    // Логотип: 0.9 ширины панели, по центру.
    private static final int LOGO = 144;
    private static final int LOGO_X = PANEL_X + (PANEL_W - LOGO) / 2;
    private static final int LOGO_Y = PANEL_Y + (PANEL_H - LOGO) / 2;

    private static final class Prepared {
        final NativeImage image;
        /** Кадры логотипа, 0xRRGGBB, LOGO x LOGO. */
        final int[][] frames;
        /** Фон под логотипом, 0xRRGGBB. */
        final int[] background;
        /** Плавное затухание к краям кадра, 0..255. */
        final int[] mask;

        Prepared(NativeImage image, int[][] frames, int[] background, int[] mask) {
            this.image = image;
            this.frames = frames;
            this.background = background;
            this.mask = mask;
        }
    }

    private static volatile Prepared prepared;
    private static volatile boolean loading;
    private static volatile boolean failed;

    private static boolean registered;
    private static DynamicTexture texture;
    private static GpuSampler sampler;
    private static int lastIndex = -1;

    private AnimatedLogoCapeTexture() {}

    /**
     * Текстура анимированного плаща или {@code null}, если она ещё готовится
     * (или недоступна) — тогда нужно показывать обычный плащ. Вызывать на render-потоке.
     */
    public static Identifier current() {
        if (failed) return null;
        Prepared p = prepared;
        if (p == null) {
            if (!loading) startLoad();
            return null;
        }
        if (!registered && !register(p)) return null;

        int index = (int) ((System.currentTimeMillis() / FRAME_MS) % FRAMES);
        if (index != lastIndex) {
            lastIndex = index;
            compose(p, index);
            texture.upload();
        }
        return ID;
    }

    private static void startLoad() {
        loading = true;
        Thread worker = new Thread(() -> {
            try {
                prepared = prepare();
            } catch (Throwable t) {
                System.err.println("[weppos] animated cape load failed: " + t);
                failed = true;
            }
        }, "vesence-CapeLogoLoader");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    private static Prepared prepare() throws Exception {
        ClassLoader cl = AnimatedLogoCapeTexture.class.getClassLoader();

        // --- обычный плащ, уменьшенный вдвое ---
        BufferedImage cape;
        try (InputStream in = cl.getResourceAsStream(CAPE_SOURCE)) {
            if (in == null) throw new IllegalStateException("нет " + CAPE_SOURCE);
            cape = ImageIO.read(in);
        }
        int sw = cape.getWidth();
        int sh = cape.getHeight();
        int[] src = cape.getRGB(0, 0, sw, sh, null, 0, sw);
        int w = sw / 2;
        int h = sh / 2;
        int[] base = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                base[y * w + x] = downsample(src, sw, x * 2, y * 2);
            }
        }

        // --- фон внешней панели: гладкий градиент по правой кромке (там нет логотипа) ---
        int[] rowRgb = new int[PANEL_H];
        int stripX0 = PANEL_X + PANEL_W - 11;
        for (int j = 0; j < PANEL_H; j++) {
            long r = 0, g = 0, b = 0;
            int n = 0;
            for (int x = stripX0; x < PANEL_X + PANEL_W; x++) {
                int px = base[(PANEL_Y + j) * w + x];
                r += (px >> 16) & 255;
                g += (px >> 8) & 255;
                b += px & 255;
                n++;
            }
            rowRgb[j] = ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
        }
        int[] smooth = new int[PANEL_H];
        final int radius = 6;
        for (int j = 0; j < PANEL_H; j++) {
            long r = 0, g = 0, b = 0;
            int n = 0;
            for (int k = Math.max(0, j - radius); k <= Math.min(PANEL_H - 1, j + radius); k++) {
                r += (rowRgb[k] >> 16) & 255;
                g += (rowRgb[k] >> 8) & 255;
                b += rowRgb[k] & 255;
                n++;
            }
            smooth[j] = ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
        }
        for (int j = 0; j < PANEL_H; j++) {
            for (int i = 0; i < PANEL_W; i++) {
                base[(PANEL_Y + j) * w + PANEL_X + i] = 0xFF000000 | smooth[j];
            }
        }

        NativeImage image = new NativeImage(w, h, true);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setPixel(x, y, base[y * w + x]);
            }
        }

        // --- подложка и маска под логотип ---
        int[] background = new int[LOGO * LOGO];
        int[] mask = new int[LOGO * LOGO];
        double c = (LOGO - 1) / 2.0;
        double rad = LOGO / 2.0;
        for (int j = 0; j < LOGO; j++) {
            int bg = smooth[LOGO_Y - PANEL_Y + j];
            for (int i = 0; i < LOGO; i++) {
                background[j * LOGO + i] = bg;
                double d = Math.hypot(i - c, j - c) / rad;
                double t = Math.max(0.0, Math.min(1.0, (0.98 - d) / 0.38));
                mask[j * LOGO + i] = (int) Math.round(255 * t * t * (3 - 2 * t));
            }
        }

        // --- кадры логотипа ---
        int[][] frames = new int[FRAMES][];
        for (int k = 0; k < FRAMES; k++) {
            try (InputStream in = cl.getResourceAsStream(String.format(FRAME_PATTERN, k * FRAME_STEP))) {
                if (in == null) throw new IllegalStateException("нет кадра " + (k * FRAME_STEP));
                frames[k] = scaleFrame(ImageIO.read(in));
            }
        }

        return new Prepared(image, frames, background, mask);
    }

    /** Кадр 320x320 → LOGO x LOGO: сначала бикубикой до 2*LOGO, затем усреднение 2x2. */
    private static int[] scaleFrame(BufferedImage srcImg) {
        int big = LOGO * 2;
        BufferedImage scaled = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(srcImg, 0, 0, big, big, null);
        g.dispose();
        int[] px = scaled.getRGB(0, 0, big, big, null, 0, big);
        int[] out = new int[LOGO * LOGO];
        for (int y = 0; y < LOGO; y++) {
            for (int x = 0; x < LOGO; x++) {
                int r = 0, gg = 0, b = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int p = px[(y * 2 + dy) * big + x * 2 + dx];
                        int a = p >>> 24;
                        // Если у кадра есть прозрачность — считаем её чёрным (для режима «экран»).
                        r += ((p >> 16) & 255) * a / 255;
                        gg += ((p >> 8) & 255) * a / 255;
                        b += (p & 255) * a / 255;
                    }
                }
                out[y * LOGO + x] = ((r / 4) << 16) | ((gg / 4) << 8) | (b / 4);
            }
        }
        return out;
    }

    /** Среднее 2x2 с весом по альфе, чтобы по краю плаща не появлялась тёмная кайма. */
    private static int downsample(int[] src, int stride, int x, int y) {
        int c0 = src[y * stride + x];
        int c1 = src[y * stride + x + 1];
        int c2 = src[(y + 1) * stride + x];
        int c3 = src[(y + 1) * stride + x + 1];
        int a0 = c0 >>> 24, a1 = c1 >>> 24, a2 = c2 >>> 24, a3 = c3 >>> 24;
        int aSum = a0 + a1 + a2 + a3;
        if (aSum == 0) return 0;
        int r = (((c0 >> 16) & 255) * a0 + ((c1 >> 16) & 255) * a1 + ((c2 >> 16) & 255) * a2 + ((c3 >> 16) & 255) * a3) / aSum;
        int g = (((c0 >> 8) & 255) * a0 + ((c1 >> 8) & 255) * a1 + ((c2 >> 8) & 255) * a2 + ((c3 >> 8) & 255) * a3) / aSum;
        int b = ((c0 & 255) * a0 + (c1 & 255) * a1 + (c2 & 255) * a2 + (c3 & 255) * a3) / aSum;
        return ((aSum / 4) << 24) | (r << 16) | (g << 8) | b;
    }

    private static boolean register(Prepared p) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (sampler == null) {
                sampler = RenderSystem.getSamplerCache().getSampler(
                        AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                        FilterMode.LINEAR, FilterMode.LINEAR, false);
            }
            compose(p, 0);
            DynamicTexture tex = new DynamicTexture(() -> "vesence_cape_logo", p.image);
            try {
                ((AbstractTextureAccessor) (Object) tex).vesence$setSampler(sampler);
            } catch (Throwable ignored) {
            }
            mc.getTextureManager().register(ID, tex);
            tex.upload();
            texture = tex;
            registered = true;
            return true;
        } catch (Throwable t) {
            System.err.println("[weppos] animated cape register failed: " + t);
            failed = true;
            return false;
        }
    }

    /** Накладывает кадр логотипа на фон панели режимом «экран». */
    private static void compose(Prepared p, int index) {
        int[] frame = p.frames[index];
        for (int j = 0; j < LOGO; j++) {
            for (int i = 0; i < LOGO; i++) {
                int k = j * LOGO + i;
                int f = frame[k];
                int m = p.mask[k];
                int bg = p.background[k];
                int fr = ((f >> 16) & 255) * m / 255;
                int fg = ((f >> 8) & 255) * m / 255;
                int fb = (f & 255) * m / 255;
                int br = (bg >> 16) & 255;
                int bgG = (bg >> 8) & 255;
                int bb = bg & 255;
                int r = 255 - (255 - br) * (255 - fr) / 255;
                int g = 255 - (255 - bgG) * (255 - fg) / 255;
                int b = 255 - (255 - bb) * (255 - fb) / 255;
                p.image.setPixel(LOGO_X + i, LOGO_Y + j, 0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }
    }
}
