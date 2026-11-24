package vesence.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import vesence.Vesence;
import vesence.renderengine.providers.GlState;
import vesence.renderengine.render.Renderer2D;
import vesence.utils.render.ColorUtil;
import vesence.utils.render.text.FontRegistry;

/**
 * Приветственный экран после загрузки клиента.
 *
 * <p>Показывается один раз за запуск: сплэш-оверлей догорает, затем появляется
 * курсивное «Добро пожаловать», и через {@link #DURATION_MS} открывается главное меню.
 * Повторный возврат в меню (после выхода из мира и т.п.) сразу ведёт в {@link MainScreen}.
 */
@Environment(EnvType.CLIENT)
public class WelcomeScreen extends Screen {

    private static final long FADE_IN_MS = 300L;
    private static final long DURATION_MS = 1800L;
    private static final float FADE_OUT_SEC = 0.22f;

    private static boolean launched = false;

    private long startMillis = 0L;
    private boolean finished = false;

    /** Первый за запуск клиента TitleScreen ведём на приветствие, дальше — сразу в меню. */
    public static Screen createForLaunch() {
        if (launched) {
            return new MainScreen();
        }
        return new WelcomeScreen();
    }

    public WelcomeScreen() {
        super(Component.literal("Welcome"));
        launched = true;
    }

    @Override
    protected void init() {
        super.init();
        MainScreen.ensureMenuMusic();
        MainScreen.preloadLogo();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || !Vesence.isModInitialized()) {
            context.fill(0, 0, this.width, this.height, 0xFF000000);
            return;
        }

        // Экран впервые отрисовывается, когда сплэш начинает гаснуть: приветствие
        // проявляется под ним и к моменту полного исчезновения уже видно.
        if (this.startMillis == 0L) {
            this.startMillis = System.currentTimeMillis();
        }

        int width = mc.getWindow().getScreenWidth();
        int height = mc.getWindow().getScreenHeight();
        if (width <= 0 || height <= 0) return;

        Vesence.deferOverlay(() -> drawWelcome(mc, width, height));
    }

    private void drawWelcome(Minecraft mc, int width, int height) {
        GlState.Snapshot snapshot = GlState.push();
        int tempFbo = vesence.renderengine.MainTargetBinder.begin();
        try {
            Vesence.ensureRendererInitialized();
            Renderer2D renderer = Vesence.getRenderer();
            if (renderer != null) {
                renderer.begin(width, height);
                try {
                    renderContent(renderer, width, height);
                } finally {
                    renderer.end();
                }
            }
        } catch (Exception e) {
            System.err.println("[weppos] WelcomeScreen render error: " + e);
        } finally {
            vesence.renderengine.MainTargetBinder.end(tempFbo);
            GlState.pop(snapshot);
        }
    }

    private void renderContent(Renderer2D r, int sw, int sh) {
        float elapsed = (System.currentTimeMillis() - this.startMillis) / 1000f;
        float p = Math.min(1f, elapsed / (FADE_IN_MS / 1000f));
        // Плавный выезд снизу + проявление.
        float eased = 1f - (1f - p) * (1f - p);
        float slide = (1f - eased) * 22f;
        // Перед переходом в меню текст плавно гаснет.
        float fadeOut = Math.min(1f, Math.max(0f, (DURATION_MS / 1000f - elapsed) / FADE_OUT_SEC));
        float alpha = Math.min(eased, fadeOut);

        // Фон как в главном меню — размытый свет логотипа, чтобы переход был бесшовным.
        MainScreen.drawWash(r, sw, sh);

        float cx = sw / 2f;
        float cy = sh / 2f;
        float size = Math.min(96f, sh * 0.11f);
        int accent = ColorUtil.getColor(237, 77, 104, 255);

        r.pushAlpha(alpha);

        // Чистая геометрия Montserrat лучше читается на крупном приветствии.
        float ty = cy + slide;

        // Белый текст остаётся главным; акцент используется только в подсветке фона.
        r.textBlur(FontRegistry.MONTSERRAT, cx, ty, size, "Добро пожаловать",
                ColorUtil.getColor(255, 255, 255, 75), "c", 0.045f, size * 0.16f);

        // Ровная белая заливка без цветового градиента.
        r.text(FontRegistry.MONTSERRAT, cx, ty, size, "Добро пожаловать",
                new int[]{
                        ColorUtil.getColor(255, 255, 255, 255),
                        ColorUtil.getColor(255, 255, 255, 255),
                        ColorUtil.getColor(255, 255, 255, 255)
                },
                false, 0f, "c", 0.045f);

        // Акцентная линия под текстом.
        float lineW = size * 1.25f * eased;
        float lineY = ty + size * 0.44f;
        r.rect(cx - lineW * 0.5f, lineY, lineW, Math.max(1.5f, sh * 0.002f), accent);

        r.popAlpha();
    }

    @Override
    public void tick() {
        if (this.finished || this.startMillis == 0L) {
            return;
        }
        if (System.currentTimeMillis() - this.startMillis >= DURATION_MS) {
            openMenu();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean bl) {
        openMenu();
        return true;
    }

    @Override
    public void onClose() {
        openMenu();
    }

    private void openMenu() {
        if (this.finished) {
            return;
        }
        this.finished = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.gui.setScreen(new MainScreen());
        }
    }
}
