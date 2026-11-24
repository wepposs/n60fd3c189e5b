package vesence.ui;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.renderer.texture.AbstractTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import smile.stat.Hypothesis;
import vesence.Vesence;
import vesence.hmi.script_wrappers.C;
import vesence.renderengine.providers.GlState;
import vesence.renderengine.render.BlendMode;
import vesence.renderengine.render.Renderer2D;
import vesence.utils.render.BorderRadius;
import vesence.utils.render.ColorUtil;
import vesence.utils.render.math.animation.anim.util.Animation2;
import vesence.utils.render.math.animation.anim.util.Easings;
import vesence.utils.render.text.ColorFormat;
import vesence.utils.render.text.FontObject;
import vesence.utils.render.text.FontRegistry;
import vesence.utils.render.ttf.TtfFonts;

import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class MainScreen extends Screen {

    private static final float BTN_W       = 210;
    private static final float BTN_H       = 75;
    private static final float COMPACT_H   = 32;
    private static final float TOP_ROW_H   = 110;
    private static final float BOT_ROW_H   = 37;
    private static final float ICON_SIZE   = 22;
    private static final float BTN_RADIUS  = 5;
    private static final float BTN_BLUR_A  = 0.55f;

    private static final float GRID_COLS = 2;
    private static final float COL_GAP = 10;
    private static final float ROW_GAP = 7;
    private static final float DESC_SIZE = 13;
    private static final float DESC_LINE_GAP = -2;

    private static final float TITLE_SIZE = 55;
    private static final float TITLE_GAP  = 32f;

    // Логотип анимирован; фон меню — его же кадры, размытые на весь экран (drawWash).
    private static final String LOGO_VIDEO_RESOURCE = "assets/vesence/videos/logo";
    private VideoBackground logoVideo;

    private static final float CHAR_DROP_HEIGHT = 30f;
    private static final float CHAR_ANIM_DURATION = 0.5f;
    private static final float CHAR_DELAY = 0.06f;
    private static final float BTN_ANIM_DURATION = 0.6f;
    private static final float BTN_DELAY = 0.1f;
    private static final float BTN_START_DELAY = 0.3f;

    private static final float INTRO_DURATION   = 0.55f;
    private static final float INTRO_STAGGER     = 0.06f;
    private static final float INTRO_Y_SLIDE     = 26f;
    private static final float TEXT_BLUR_STRENGTH = 10f;
    private long introStartMillis = 0L;
    private int lastLangMode = -1;


    private static volatile boolean handlerRegistered = false;
    private static boolean menuMusicStarted = false;
    private static final float MENU_MUSIC_VOLUME = 0.25f;
    private static final float MENU_MUSIC_VOLUME_ALT = 0.08f;
    Renderer2D renderer2D;
    private final SettingsWindow settingsWindow = new SettingsWindow();
    private final AccountSwitcherWindow accountSwitcher = new AccountSwitcherWindow();

    private static final float ICON_BTN_SIZE = 16;

    // Единая палитра: почти чёрная база + один акцент.
    private static final int C_ACCENT            = ColorUtil.getColor(237, 77, 104, 255);
    private static final int C_BUTTON_FILL       = ColorUtil.getColor(11, 11, 15, 255);

    private final Animation2 langHoverAnim = new Animation2();
    private final Animation2 langPressAnim = new Animation2();
    private final Animation2 profileHoverAnim = new Animation2();
    private final Animation2 profilePressAnim = new Animation2();
    private float profileFBX, profileFBY, profileFBW, profileFBH;

    public static void registerEventHandler() {
        handlerRegistered = true;
    }

    private static class Button {
        String name;
        final String icon;
        String description;
        final Runnable action;
        final Animation2 hoverAnim = new Animation2();
        final Animation2 pressAnim = new Animation2();
        final Animation2 expandAnim = new Animation2();
        float x, y, w, h;

        Button(String name, String icon, String description, Runnable action) {
            this.name = name;
            this.icon = icon;
            this.description = description;
            this.action = action;
            hoverAnim.set(0.0);
            pressAnim.set(0.0);
            expandAnim.set(0.0);
        }
    }

    private final List<Button> buttons = new ArrayList<>();
    private final Animation2 openAnim = new Animation2();
    private boolean mouseDown = false;
    private int pressedIndex = -1;
    private float smoothedMouseX = Float.NaN;
    private float smoothedMouseY = Float.NaN;
    private long rainStartMillis;
    private float currentMenuScale = 1.25f;
    private float currentOffsetY = 0f;
    private float uniformExpandedH = COMPACT_H;
    private float langBtnFBX, langBtnFBY, langBtnFBW, langBtnFBH;

    private float langRuX, langRuW, langEnX, langEnW, langY, langH;

    private String t(String ru, String eng) {
        return settingsWindow.t(ru, eng);
    }

    private float buttonHeight(int index) {
        return 64f;
    }

    private float buttonWidth(int index) {
        return 270f;
    }

    private boolean splashFadeStarted(Minecraft mc) {
        net.minecraft.client.gui.screens.Overlay overlay = mc.gui.overlay();
        if (overlay == null) return true;
        if (overlay instanceof net.minecraft.client.gui.screens.LoadingOverlay) {
            try {
                long t = ((vesence.mixin.SplashOverlayAccessor) overlay).getReloadCompleteTime();
                return t > -1L;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private float introProgress(int order) {
        if (introStartMillis == 0L) return 1f;
        float elapsed = (System.currentTimeMillis() - introStartMillis) / 1000f;
        float local = (elapsed - order * INTRO_STAGGER) / INTRO_DURATION;
        if (local <= 0f) return 0f;
        if (local >= 1f) return 1f;
        return (float) Easings.CUBIC_OUT.ease(local);
    }

    private void introText(Renderer2D r, FontObject fo, float x, float y, float size, String s,
                           int color, float letterSpacing, int order, boolean blur) {
        float p = introProgress(order);
        float yOff = (1f - p) * INTRO_Y_SLIDE;
        int baseA = (color >>> 24) & 0xFF;
        int a = Math.round(baseA * p);
        if (a <= 1) return;
        int col = ColorUtil.replAlpha(color, a);
        r.text(fo, x, y + yOff, size, s, col, letterSpacing);
        if (blur) {
            r.textBlur(fo, x, y + yOff, size, s, col, letterSpacing, TEXT_BLUR_STRENGTH);
        }
    }

    private void introText(Renderer2D r, FontObject fo, float x, float y, float size, String s,
                           int color, float letterSpacing, int order) {
        introText(r, fo, x, y, size, s, color, letterSpacing, order, false);
    }

    private void introText(Renderer2D r, FontObject fo, float x, float y, float size, String s,
                           int[] colors, float letterSpacing, int order, boolean blur) {
        float p = introProgress(order);
        float yOff = (1f - p) * INTRO_Y_SLIDE;
        if (p <= 0.01f) return;
        r.pushAlpha(p);
        try {
            r.text(fo, x, y + yOff, size, s, colors, 0, letterSpacing);
            if (blur) {
                r.textBlur(fo, x, y + yOff, size, s, colors, letterSpacing, TEXT_BLUR_STRENGTH);
            }
        } finally {
            r.popAlpha();
        }
    }

    private void introText(Renderer2D r, FontObject fo, float x, float y, float size, String s,
                           int[] colors, float letterSpacing, int order) {
        introText(r, fo, x, y, size, s, colors, letterSpacing, order, false);
    }

    private float toLogicalX(float fbX, float sw) {
        float cx = sw / 2f;
        return (fbX - cx) / currentMenuScale + cx;
    }

    private float toLogicalY(float fbY, float sh) {
        float cy = sh / 2f;
        return (fbY - cy - currentOffsetY) / currentMenuScale + cy;
    }

    public MainScreen() {
        super(Component.literal("weppos Menu"));
    }

    @Override
    public void init() {
        super.init();
        buttons.clear();

        Minecraft mc = Minecraft.getInstance();

        buttons.add(new Button("SinglePlayer", "e",
                "Here you can visually inspect and\nexperience functions without\nany restrictions with anti cheat", () ->
                mc.gui.setScreen(new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(this))));
        buttons.add(new Button("MultiPlayer", "x",
                "Here you can experience all the\nfunctionality of the client on\ndifferent servers", () ->
                mc.gui.setScreen(new vesence.ui.MultiplayerScreen(this).setRussian(settingsWindow.getSelectedLangMode() == 0))));
        buttons.add(new Button("Accounts", "y",
                "Manage your accounts", () ->
                accountSwitcher.open()));
        buttons.add(new Button("Settings", "h",
                "Here you can customize the game\nalong and across, removing\nunnecessary elementals.", () ->
                mc.gui.setScreen(new OptionsScreen(this, mc.options))));
        buttons.add(new Button("Quit", "A",
                "Meet me after a while again :)", () ->
                mc.stop()));

        openAnim.set(1.0);
        introStartMillis = 0L;
        settingsWindow.reset();
        smoothedMouseX = Float.NaN;
        smoothedMouseY = Float.NaN;
        rainStartMillis = System.currentTimeMillis();

        // Видео-фон: создаём декодер один раз (init() вызывается повторно при
        // ресайзе окна), при повторном показе меню перезапускаем воспроизведение.
        // Видео-фон: кешируется на сессию; декод стартует в фоне при первом обращении.
        preloadLogo();

        ensureMenuMusic();
    }

    public static void ensureMenuMusic() {
        if (!menuMusicStarted) {
            menuMusicStarted = true;
            Thread musicThread = new Thread(
                    () -> vesence.utils.render.utils.SoundUtil.playSound_mp3("menu_theme", MENU_MUSIC_VOLUME, true),
                    "vesence-MenuMusic");
            musicThread.setDaemon(true);
            musicThread.start();
        }
    }

    public static void stopMenuMusic() {
        menuMusicStarted = false;
        try {
            vesence.utils.render.utils.SoundUtil.stopCurrent();
        } catch (Throwable ignored) {
        }
    }

    public static boolean isMenuMusicActive() {
        return menuMusicStarted;
    }

    /**
     * Свет логотипа, размытый на весь экран: общий фон главного меню и приветствия.
     * Кадр видео растягивается с запасом, дважды размывается, края уходят в виньетку.
     */
    // Кадры логотипа (250 PNG, 50 fps, 320x320) проигрываем сами по часам:
    // потоковый декодер не успевал и анимация шла рывками.
    private static final int LOGO_FRAMES = 250;
    private static final double LOGO_FPS = 50.0;
    private static final Identifier[] LOGO_IDS = new Identifier[LOGO_FRAMES];
    static {
        for (int i = 0; i < LOGO_FRAMES; i++) {
            LOGO_IDS[i] = Identifier.fromNamespaceAndPath("vesence", String.format("videos/logo/f%04d.png", i));
        }
    }
    private static boolean logoPreloaded = false;

    /** Загружает все кадры в видеопамять заранее, чтобы первый круг не подтормаживал. */
    static void preloadLogo() {
        if (logoPreloaded) return;
        logoPreloaded = true;
        try {
            Minecraft mc = Minecraft.getInstance();
            for (Identifier id : LOGO_IDS) mc.getTextureManager().getTexture(id);
        } catch (Throwable t) {
            System.err.println("[weppos] logo preload failed: " + t);
        }
    }

    static Identifier logoFrame() {
        long idx = (long) (System.nanoTime() / 1_000_000_000.0 * LOGO_FPS);
        return LOGO_IDS[(int) (idx % LOGO_FRAMES)];
    }

    static void drawWash(Renderer2D r, int sw, int sh) {
        r.rect(0, 0, sw, sh, ColorUtil.getColor(0, 0, 0, 255));
        {
            float size = Math.max(sw, sh) * 0.85f;
            r.drawImage(logoFrame(), (sw - size) / 2f, (sh - size) / 2f - sh * 0.04f, size, size,
                    ColorUtil.getColor(255, 255, 255, 190), false, 0f);
            float radius = Math.max(40f, sh * 0.05f);
            r.blur(0, 0, sw, sh, radius, 0f, 1f);
            r.blur(0, 0, sw, sh, radius, 0f, 1f);
        }
        int edge = ColorUtil.getColor(0, 0, 0, 185);
        int clear = ColorUtil.getColor(0, 0, 0, 0);
        float vh = sh * 0.36f;
        float vw = sw * 0.26f;
        r.verticalGradient(0, 0, sw, vh, edge, clear);
        r.verticalGradient(0, sh - vh, sw, vh, clear, edge);
        r.horizontalGradient(0, 0, vw, sh, edge, clear);
        r.horizontalGradient(sw - vw, 0, vw, sh, clear, edge);
    }

    private void updateButtonTranslations() {
        if (buttons.size() < 5) return;
        boolean ru = settingsWindow.getSelectedLangMode() == 0;
        buttons.get(0).name = ru ? "Одиночная игра" : "Singleplayer";
        buttons.get(1).name = ru ? "Играть" : "Play";
        buttons.get(2).name = ru ? "Аккаунты" : "Accounts";
        buttons.get(3).name = ru ? "Настройки" : "Settings";
        buttons.get(4).name = ru ? "Выход" : "Quit";
    }

    private static int getGlId(Identifier id) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return 0;
            AbstractTexture tex = mc.getTextureManager().getTexture(id);
            if (tex == null) return 0;
            GpuTextureView view = tex.getTextureView();
            if (view == null) return 0;
            GpuTexture gpuTex = view.texture();
            if (gpuTex instanceof GlTexture glTex) return glTex.glId();
        } catch (Exception ignored) {}
        return 0;
    }

    private static boolean inside(float px, float py, float x, float y, float w, float h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private float hoverOf(Animation2 anim, boolean hover) {
        anim.update();
        anim.run(hover ? 1.0 : 0.0, 0.25, Easings.CUBIC_OUT);
        return (float) anim.get();
    }

    private void renderVesence(Renderer2D r, int sw, int sh, Minecraft mc) {
        if (r == null || mc.getWindow() == null) return;
        renderer2D = r;
        if (introStartMillis == 0L && splashFadeStarted(mc)) introStartMillis = System.currentTimeMillis();

        float mx = (float)(mc.mouseHandler.xpos() * sw / mc.getWindow().getWidth());
        float my = (float)(mc.mouseHandler.ypos() * sh / mc.getWindow().getHeight());
        updateSmoothedMouse(mx, my);
        openAnim.update();
        float open = (float) openAnim.get();
        currentMenuScale = 1f;
        currentOffsetY = 0f;
        // Наведение — по реальному курсору, а не сглаженному: иначе подсветка запаздывает.
        float lx = mx;
        float ly = my;
        boolean interactive = !settingsWindow.isOpen() && !accountSwitcher.isOpen();

        updateButtonTranslations();
        drawWash(r, sw, sh);

        final int white = ColorUtil.getColor(255, 255, 255, 255);
        final float cx = sw / 2f;
        final float edge = sh * 0.042f;

        // Весь текст — через TtfFonts: Java растрит глифы ровно под размер, а координаты
        // целые, поэтому буквы ложатся 1:1 на пиксели и остаются чёткими.
        // (Атласные шрифты FontRegistry на мелком кегле «мылятся».)

        // --- верх: имя слева, язык справа ---
        int topSize = Math.round(sh * 0.026f);
        int topY = Math.round(edge);
        r.pushAlpha(open * introProgress(3));
        TtfFonts.SF_BOLD.drawString(r, "weppos", Math.round(edge), topY, topSize, white);

        int langSize = Math.round(sh * 0.021f);
        boolean ru = settingsWindow.getSelectedLangMode() == 0;
        float enW = TtfFonts.SF_SEMIBOLD.getStringWidth("EN", langSize);
        float ruW = TtfFonts.SF_SEMIBOLD.getStringWidth("RU", langSize);
        float langGap = Math.round(langSize * 1.1f);
        float langLine = TtfFonts.SF_SEMIBOLD.getHeight(langSize);
        langEnX = Math.round(sw - edge - enW);
        langRuX = Math.round(langEnX - langGap - ruW);
        langRuW = ruW;
        langEnW = enW;
        langY = topY + (TtfFonts.SF_BOLD.getHeight(topSize) - langLine) / 2f;
        langH = langLine;
        float ruHover = hoverOf(langHoverAnim, interactive && !ru && inside(lx, ly, langRuX, langY, ruW, langH));
        float enHover = hoverOf(langPressAnim, interactive && ru && inside(lx, ly, langEnX, langY, enW, langH));
        TtfFonts.SF_SEMIBOLD.drawString(r, "RU", langRuX, Math.round(langY), langSize,
                ru ? white : ColorUtil.overCol(ColorUtil.getColor(255, 255, 255, 105), white, ruHover));
        TtfFonts.SF_SEMIBOLD.drawString(r, "EN", langEnX, Math.round(langY), langSize,
                !ru ? white : ColorUtil.overCol(ColorUtil.getColor(255, 255, 255, 105), white, enHover));
        r.popAlpha();

        // --- центр: логотип, название, кнопка ---
        // Кадры логотипа 320x320: больше ~1.3x от родного размера уже мылится.
        float orb = Math.round(Math.min(sh * 0.42f, 320f * 1.3f));
        int titleSize = Math.round(sh * 0.058f);
        float titleLine = TtfFonts.SF_BOLD.getHeight(titleSize);
        int btnH = Math.round(sh * 0.068f);
        float gap = sh * 0.03f;
        float blockH = orb + titleLine + gap + btnH;
        float orbY = Math.round((sh - blockH) / 2f - sh * 0.02f);

        {
            Identifier frame = logoFrame();
            float floatY = (float) Math.sin(System.currentTimeMillis() / 1600.0) * sh * 0.004f;
            r.pushAlpha(open * introProgress(0));
            // SCREEN: тёмный фон кадра исчезает, остаётся только светящийся знак.
            r.pushBlendMode(BlendMode.SCREEN);
            r.drawImage(frame, Math.round(cx - orb / 2f), orbY + floatY, orb, orb, -1, false, orb / 2f);
            r.popBlendMode();
            r.popAlpha();
        }

        String titleA = "weppos";
        String titleB = " client";
        float wa = TtfFonts.SF_BOLD.getStringWidth(titleA, titleSize);
        float wb = TtfFonts.SF_MEDIUM.getStringWidth(titleB, titleSize);
        float titleX = Math.round(cx - (wa + wb) / 2f);
        float titleY = Math.round(orbY + orb);
        float pt = introProgress(1);
        float slideT = Math.round((1f - pt) * INTRO_Y_SLIDE);
        r.pushAlpha(open * pt);
        TtfFonts.SF_BOLD.drawString(r, titleA, titleX, titleY + slideT, titleSize, white);
        TtfFonts.SF_MEDIUM.drawString(r, titleB, Math.round(titleX + wa), titleY + slideT, titleSize,
                ColorUtil.getColor(255, 255, 255, 115));
        r.popAlpha();

        Button play = buttons.get(1);
        int playFont = Math.round(btnH * 0.36f);
        float btnW = Math.round(Math.max(btnH * 3.6f, TtfFonts.SF_SEMIBOLD.getStringWidth(play.name, playFont) + btnH * 1.6f));
        play.x = Math.round(cx - btnW / 2f);
        play.y = Math.round(titleY + titleLine + gap);
        play.w = btnW;
        play.h = btnH;
        float pp = introProgress(2);
        boolean playHover = interactive && pp > 0.98f && inside(lx, ly, play.x, play.y, play.w, play.h);
        float hov = hoverOf(play.hoverAnim, playHover);
        play.pressAnim.update();
        play.pressAnim.run(mouseDown && pressedIndex == 1 ? 1.0 : 0.0, 0.16, Easings.CUBIC_OUT);
        float press = (float) play.pressAnim.get();
        float scale = 1f + hov * 0.03f - press * 0.05f;
        float playY = Math.round(play.y + (1f - pp) * INTRO_Y_SLIDE);
        r.pushAlpha(open * pp);
        // Масштабируем только плашку: текст под scale стал бы нечётким.
        r.pushScale(scale, cx, playY + btnH / 2f);
        r.rect(play.x, playY, btnW, btnH, btnH * 0.26f, white);
        r.popTransform();
        float playLine = TtfFonts.SF_SEMIBOLD.getHeight(playFont);
        TtfFonts.SF_SEMIBOLD.drawCenteredString(r, play.name, Math.round(cx),
                Math.round(playY + (btnH - playLine) / 2f), playFont, ColorUtil.getColor(0, 0, 0, 255));
        r.popAlpha();

        // --- низ: остальные действия тихой строкой ---
        int[] links = {0, 2, 3, 4};
        int linkSize = Math.round(sh * 0.021f);
        float linkLine = TtfFonts.SF_SEMIBOLD.getHeight(linkSize);
        float linkGap = Math.round(sh * 0.042f);
        float total = 0f;
        for (int k = 0; k < links.length; k++) {
            total += TtfFonts.SF_SEMIBOLD.getStringWidth(buttons.get(links[k]).name, linkSize);
            if (k > 0) total += linkGap;
        }
        float linkY = Math.round(sh - edge - linkLine);
        float x = Math.round(cx - total / 2f);
        float pl = introProgress(3);
        r.pushAlpha(open * pl);
        for (int index : links) {
            Button b = buttons.get(index);
            float w = TtfFonts.SF_SEMIBOLD.getStringWidth(b.name, linkSize);
            b.x = x;
            b.y = linkY;
            b.w = w;
            b.h = linkLine;
            float h = hoverOf(b.hoverAnim, interactive && pl > 0.98f && inside(lx, ly, b.x, b.y, b.w, b.h));
            TtfFonts.SF_SEMIBOLD.drawString(r, b.name, x, linkY, linkSize,
                    ColorUtil.overCol(ColorUtil.getColor(255, 255, 255, 128), white, h));
            x += w + linkGap;
        }
        r.popAlpha();

        if (!accountSwitcher.isOpen()) settingsWindow.renderWindow(r, sw, sh, lx, ly, sw / 2f);

        accountSwitcher.setRussian(settingsWindow.getSelectedLangMode() == 0);
        float dim = accountSwitcher.getSlide();
        if (dim > 0.001f) {
            r.blur(0, 0, sw, sh, 18, 0, dim);
            r.rect(0, 0, sw, sh, ColorUtil.getColor(0,0,0,Math.round(150 * dim)));
        }
        try { vesence.utils.render.utils.SoundUtil.setCurrentVolume(MENU_MUSIC_VOLUME + (MENU_MUSIC_VOLUME_ALT - MENU_MUSIC_VOLUME) * dim); }
        catch (Throwable ignored) {}
        accountSwitcher.render(r, sw, sh, mx, my);
    }

    private void updateSmoothedMouse(float targetX, float targetY) {
        if (Float.isNaN(smoothedMouseX) || Float.isNaN(smoothedMouseY)) {
            smoothedMouseX = targetX;
            smoothedMouseY = targetY;
            return;
        }

        float smoothing = 0.12f;
        smoothedMouseX += (targetX - smoothedMouseX) * smoothing;
        smoothedMouseY += (targetY - smoothedMouseY) * smoothing;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || !Vesence.isModInitialized()) {
            context.fill(0, 0, this.width, this.height, 0xFF000000);
            return;
        }

        int width = mc.getWindow().getScreenWidth();
        int height = mc.getWindow().getScreenHeight();
        if (width <= 0 || height <= 0) return;

        // 26.3: extract-фаза лишь собирает состояние кадра, рисовать в неё нельзя —
        // кадр очищается в GameRenderer.render(). Кладём отрисовку в пост-GUI проход.
        Vesence.deferOverlay(() -> drawVesenceFrame(mc, width, height));
    }

    private void drawVesenceFrame(Minecraft mc, int width, int height) {
        GlState.Snapshot snapshot = GlState.push();
        int tempFbo = vesence.renderengine.MainTargetBinder.begin();
        try {
            Vesence.ensureRendererInitialized();
            Renderer2D renderer = Vesence.getRenderer();
            if (renderer != null) {
                renderer.begin(width, height);
                try {
                    renderVesence(renderer, width, height, mc);
                } finally {
                    renderer.end();
                }
            }
        } catch (Exception e) {
            System.err.println("[weppos] MainScreen render error: " + e);
        } finally {
            vesence.renderengine.MainTargetBinder.end(tempFbo);
            GlState.pop(snapshot);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean bl) {
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            mouseDown = true;
            Minecraft mc = Minecraft.getInstance();
            double scale = mc != null && mc.getWindow() != null
                    ? (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth()
                    : 1.0;
            int fbX = (int)(click.x() * scale);
            int fbY = (int)(click.y() * scale);
            int fbW = mc.getWindow().getScreenWidth();
            int fbH = mc.getWindow().getScreenHeight();
            int sw = mc.getWindow().getGuiScaledWidth();
            int sh = mc.getWindow().getGuiScaledHeight();
            float lx = toLogicalX(fbX, fbW);
            float ly = toLogicalY(fbY, fbH);
            if (vesence.debug.InputDebug.enabled()) {
                System.out.println("[input] MainScreen.mouseClicked click=" + (int) click.x() + "," + (int) click.y()
                        + " fb=" + fbX + "," + fbY + " fbWxH=" + fbW + "x" + fbH
                        + " logical=" + (int) lx + "," + (int) ly + " scale=" + currentMenuScale
                        + " offY=" + currentOffsetY);
                for (int i = 0; i < buttons.size(); i++) {
                    Button b = buttons.get(i);
                    System.out.println("[input]   button[" + i + "] '" + b.name + "' rect="
                            + (int) b.x + "," + (int) b.y + " " + (int) b.w + "x" + (int) b.h
                            + " hit=" + inside(lx, ly, b.x, b.y, b.w, b.h));
                }
            }
            if (accountSwitcher.click(fbX, fbY, fbW, fbH)) return true;

            if (!settingsWindow.isOpen() && !accountSwitcher.isOpen()) {

                if (inside(lx, ly, langRuX, langY, langRuW, langH)) {
                    if (settingsWindow.getSelectedLangMode() != 0) settingsWindow.toggleLanguage();
                    return true;
                }
                if (inside(lx, ly, langEnX, langY, langEnW, langH)) {
                    if (settingsWindow.getSelectedLangMode() == 0) settingsWindow.toggleLanguage();
                    return true;
                }
                for (int i = 0; i < buttons.size(); i++) {
                    Button btn = buttons.get(i);
                    if (inside(lx, ly, btn.x, btn.y, btn.w, btn.h)) {
                        pressedIndex = i;
                        return true;
                    }
                }
            }
            if (settingsWindow.clickWindow(lx, ly, fbW, fbH)) return true;
        }

        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent click) {
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            Minecraft mc = Minecraft.getInstance();
            double scale = mc != null && mc.getWindow() != null
                    ? (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth()
                    : 1.0;
            int fbX = (int)(click.x() * scale);
            int fbY = (int)(click.y() * scale);
            int fbW = mc.getWindow().getScreenWidth();
            int fbH = mc.getWindow().getScreenHeight();
            float lx = toLogicalX(fbX, fbW);
            float ly = toLogicalY(fbY, fbH);

            if (pressedIndex >= 0 && pressedIndex < buttons.size()) {
                Button btn = buttons.get(pressedIndex);
                if (inside(lx, ly, btn.x, btn.y, btn.w, btn.h)) {
                    btn.action.run();
                }
            }
            settingsWindow.release(lx, ly, fbW, fbH);
            accountSwitcher.release();
            mouseDown = false;
            pressedIndex = -1;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        if (accountSwitcher.isOpen()) {
            return accountSwitcher.charTyped(input);
        }
        return super.charTyped(input);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.key() == InputConstants.KEY_ESCAPE) {
            if (accountSwitcher.isOpen()) {
                accountSwitcher.handleEsc();
                return true;
            }
            if (settingsWindow.keyPressed(input.key())) return true;
        }
        if (accountSwitcher.isOpen()) {
            return accountSwitcher.keyPressed(input.key());
        }
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        if (accountSwitcher.isOpen()) return false;
        return settingsWindow.shouldCloseOnEsc();
    }

    @Override
    public void removed() {
        // Страховка: если экран сменяется при открытом попапе создания аккаунта,
        // выключаем приём текста (SDL TextInputManager).
        accountSwitcher.releaseTextInput();
        super.removed();
    }

    // 26.3: Screen#shouldPause() заменён на isPauseScreen() (true по умолчанию).
    // Главное меню мир не держит, поэтому пауза не нужна.
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (accountSwitcher.isOpen()) {
            accountSwitcher.scroll(verticalAmount);
            return true;
        }
        if (settingsWindow.isOpen()) {
            settingsWindow.scrollWindow(verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
    }
}
