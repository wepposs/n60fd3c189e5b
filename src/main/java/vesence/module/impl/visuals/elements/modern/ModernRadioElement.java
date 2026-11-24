package vesence.module.impl.visuals.elements.modern;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import vesence.module.impl.visuals.HudElement;
import vesence.renderengine.render.Renderer2D;
import vesence.utils.media.RadioPlayer;
import vesence.utils.render.ColorUtil;
import vesence.utils.render.math.animation.anim.util.Animation2;
import vesence.utils.render.math.animation.anim.util.Easings;
import vesence.utils.render.text.FontObject;
import vesence.utils.render.text.FontRegistry;

/**
 * Radio — HUD-панель в виде реального радио: прямоугольник с названием станции
 * сверху и светящимся спектром снизу. Вертикальные линии растут от нижнего края
 * вверх (как эквалайзер на магнитоле) и подсвечиваются цветом клиента; высота
 * столбиков идёт от громкости играющего потока ({@link RadioPlayer}).
 */
@Environment(EnvType.CLIENT)
public class ModernRadioElement extends HudElement {

    private static final float RADIUS   = 9f;
    private static final float PAD      = 14f;
    private static final float TITLE_SIZE = 26f;

    // Спектр (линии снизу вверх).
    private static final int   BARS     = 18;
    private static final float BAR_W    = 3f;
    private static final float BAR_GAP  = 2f;
    private static final float BAR_MIN_H = 2f;
    private static final float BAR_MAX_H = 46f;
    private static final float SPECTRUM_W = 190f;
    private static final float TITLE_GAP = 7f;  // между названием и спектром

    private static final float PANEL_H =
            PAD + TITLE_SIZE + TITLE_GAP + BAR_MAX_H + PAD;

    private final Animation2 widthAnim = new Animation2();
    private final float[] barH = new float[BARS];
    private final float[] phase = new float[BARS];
    private final float[] speed = new float[BARS];

    private float boundsW = 150f;
    private float[] shown;
    private float gainMax = 0.5f;
    private float gainMin = 0f;

    public ModernRadioElement() {
        super("Radio", 10f, 70f);
        widthAnim.set(150);
        for (int i = 0; i < BARS; i++) {
            phase[i] = (float) (Math.random() * Math.PI * 2);
            speed[i] = 3.5f + (float) Math.random() * 4.5f;
        }
    }

    private String stationName() {
        RadioPlayer.Station st = RadioPlayer.get().current();
        return st != null ? st.name() : "Радио";
    }

    @Override
    public void render(Renderer2D renderer, FontObject font, int screenWidth, int screenHeight, GuiGraphicsExtractor ctx) {
        if (FontRegistry.SF_SEMI == null) return;
        FontObject f = FontRegistry.SF_SEMI;

        RadioPlayer radio = RadioPlayer.get();
        boolean playing = radio.isPlaying();
        boolean connecting = radio.connecting();
        float level = radio.level();

        int theme = Renderer2D.ColorUtil.getClientColor();

        String name = stationName();
        String status = playing ? "LIVE" : connecting ? "•••" : "OFF";

        float nameW = renderer.measureText(f, name, TITLE_SIZE, -0.05f).width;
        float headW = nameW;

        float contentW = Math.max(SPECTRUM_W, headW);
        float targetW = PAD * 2f + contentW;

        widthAnim.update();
        widthAnim.run(targetW, 0.2, Easings.SINE_OUT, true);
        float rectW = (float) widthAnim.get();
        float rectH = PANEL_H;
        boundsW = rectW;

        // Корпус радио.
        if (isBlurEnabled()) {
            renderer.blur(x, y, rectW, rectH, 40f, RADIUS, 1f);
        }
        renderer.shadow(x, y, rectW, rectH, RADIUS, 6f, 1f,
                ColorUtil.replAlpha(ColorUtil.BLACK, 55));
        renderer.rect(x, y, rectW, rectH, RADIUS, ColorUtil.getColor(0, 0, 0, 220));
        // Тонкая рамка с оттенком темы — как подсветка дисплея.
        renderer.rectOutline(x, y, rectW, rectH, RADIUS, ColorUtil.replAlpha(theme, 40), 1f);

        renderer.pushClipRect(x, y, rectW, rectH);
        try {
            // ── Заголовок: название станции + статус ──
            float baseline = Math.round(y + PAD + TITLE_SIZE * 0.55f);
            renderer.text(f, x + PAD, baseline, TITLE_SIZE, name,
                    ColorUtil.replAlpha(-1, 245), -0.05f);

            // ── Спектр: линии снизу вверх со свечением ──
            float spectrumX = x;
            float bottom = y + rectH - PAD;
            drawSpectrum(renderer, spectrumX, rectW, bottom, theme, playing, connecting, level);
        } finally {
            renderer.popClipRect();
        }
    }

    /** Волна-спектр: кривая по частотным полосам потока (низы слева, верха справа),
     *  растёт снизу вверх — чем громче частота в песне, тем выше гребень. */
    private void drawSpectrum(Renderer2D renderer, float leftX, float width, float bottom,
                              int theme, boolean playing, boolean connecting, float level) {
        float time = System.nanoTime() / 1_000_000_000f;
        float[] src = RadioPlayer.get().bands();
        int n = src.length;

        // Сглаживаем на кадр, чтобы волна текла плавно.
        if (shown == null || shown.length != n) shown = new float[n];
        for (int i = 0; i < n; i++) {
            float t;
            if (playing) t = src[i];
            else if (connecting) t = 0.15f + 0.12f * (float) Math.sin(time * 5f - i * 0.45f);
            else t = 0f;
            shown[i] += (t - shown[i]) * (t > shown[i] ? 0.45f : 0.12f);
        }

        // Автоусиление: растягиваем текущий диапазон полос на всю высоту, чтобы
        // форма зависела от разницы частот, а не упиралась в потолок.
        float mx = 0f, mn = 1f;
        for (float v0 : shown) { mx = Math.max(mx, v0); mn = Math.min(mn, v0); }
        gainMax += (Math.max(mx, 0.08f) - gainMax) * (mx > gainMax ? 0.3f : 0.02f);
        gainMin += (mn - gainMin) * 0.05f;
        float range = Math.max(0.08f, gainMax - gainMin * 0.85f);

        int cols = (int) Math.ceil(width);
        int bright = (playing || connecting) ? 245 : 110;
        int[] fade = {60, 34, 16, 6};
        float prevTop = Float.NaN;
        for (int c = 0; c <= cols; c++) {
            float t = c / (float) cols * (n - 1);
            int i0 = (int) Math.floor(t);
            int i1 = Math.min(n - 1, i0 + 1);
            int im = Math.max(0, i0 - 1), ip = Math.min(n - 1, i1 + 1);
            float f = t - i0;
            // Катмулл-Ром — гладкая кривая через полосы.
            float p0 = shown[im], p1 = shown[i0], p2 = shown[i1], p3 = shown[ip];
            float v = 0.5f * ((2 * p1) + (-p0 + p2) * f
                    + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f * f
                    + (-p0 + 3 * p1 - 3 * p2 + p3) * f * f * f);
            v = (v - gainMin * 0.85f) / range;
            v = Math.max(0f, Math.min(1f, v));

            float u = c / (float) cols;
            float env = 1f; // волна идёт от края до края, без загиба вниз
            float h = 1f + (BAR_MAX_H - 1f) * v * env;

            float cx = leftX + c;
            float topY = bottom - h;
            float band = h / fade.length;
            for (int b = 0; b < fade.length; b++) {
                renderer.rect(cx, topY + b * band, 1.4f, band + 0.3f,
                        ColorUtil.replAlpha(theme, fade[b]));
            }
            float lt = Float.isNaN(prevTop) ? topY : Math.min(prevTop, topY);
            float lh = (Float.isNaN(prevTop) ? 0f : Math.abs(prevTop - topY)) + 1.8f;
            renderer.rect(cx, lt - 0.9f, 1.4f, lh, ColorUtil.replAlpha(theme, bright));
            prevTop = topY;
        }
    }

    @Override
    public float getWidth(Renderer2D renderer, FontObject font) {
        return boundsW;
    }

    @Override
    public float getEffectiveWidth(Renderer2D renderer, FontObject font) {
        return (float) widthAnim.get();
    }

    @Override
    public float getHeight(Renderer2D renderer, FontObject font) {
        return PANEL_H;
    }
}
