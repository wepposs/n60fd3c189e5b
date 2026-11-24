package vesence.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.server.packs.resources.ReloadInstance;

import net.minecraft.util.Mth;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vesence.Vesence;
import vesence.renderengine.providers.GlState;
import vesence.renderengine.render.Renderer2D;
import vesence.utils.render.ColorUtil;
import vesence.utils.render.text.FontRegistry;

@Environment(EnvType.CLIENT)
@Mixin(LoadingOverlay.class)
public abstract class SplashOverlayMixin {

    @Shadow private Minecraft minecraft;
    @Shadow private ReloadInstance reload;
    
    @Shadow private float currentProgress;
    @Shadow private long fadeOutStart;
    @Shadow private long fadeInStart;

    private float vesence$spin = 0f;
    private long vesence$lastNanos = 0L;

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void vesence$customSplash(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!Vesence.isModInitialized()) return;
        Minecraft mc = this.minecraft;
        if (mc == null || mc.getWindow() == null) return;

        long now = net.minecraft.util.Util.getMillis();
        // 26.3: LoadingOverlay#reloading удалён — «идёт перезагрузка» определяем по тому,
        // что fade-out ещё не начался (fadeOutStart == -1).
        boolean vesence$reloading = this.fadeOutStart == -1L;
        if (vesence$reloading && this.fadeInStart == -1L) this.fadeInStart = now;

        float fadeOut = this.fadeOutStart > -1L ? (now - this.fadeOutStart) / 1000f : -1f;

        float fadeIn = this.fadeInStart > -1L ? (now - this.fadeInStart) / 500f : -1f;

        float alpha;
        if (fadeOut >= 1f) {
            alpha = 1f - Mth.clamp(fadeOut - 1f, 0f, 1f);
        } else if (vesence$reloading) {
            alpha = Mth.clamp(fadeIn, 0f, 1f);
        } else {
            alpha = 1f;
        }

        float target = this.reload.getActualProgress();
        this.currentProgress = Mth.clamp(this.currentProgress * 0.95f + target * 0.05000001f, 0f, 1f);

        ci.cancel();

        int width = mc.getWindow().getScreenWidth();
        int height = mc.getWindow().getScreenHeight();
        if (width <= 0 || height <= 0) return;

        if (fadeOut >= 1f && mc.gui.screen() != null) {
            try { mc.gui.screen().extractRenderState(context, 0, 0, deltaTicks); } catch (Exception ignored) {}
        }

        // 26.3: extract-фаза только собирает состояние кадра — он очищается в начале
        // GameRenderer.render(). Поэтому сам сплэш рисуем в пост-GUI проходе.
        final float drawAlpha = alpha;
        Vesence.deferOverlay(() -> vesence$draw(mc, width, height, drawAlpha));

        if (fadeOut >= 2f) {
            mc.gui.setOverlay(null);
        }
    }

    private void vesence$draw(Minecraft mc, int width, int height, float alpha) {

        long nowNanos = System.nanoTime();
        if (vesence$lastNanos != 0L) {
            float dt = (nowNanos - vesence$lastNanos) / 1_000_000_000f;
            vesence$spin += dt * 320f;
            vesence$spin %= 360f;
        }
        vesence$lastNanos = nowNanos;

        GlState.Snapshot snapshot = GlState.push();
        int tempFbo = vesence.renderengine.MainTargetBinder.begin();
        try {
            Vesence.ensureRendererInitialized();
            Renderer2D r = Vesence.getRenderer();
            if (r != null) {
                r.begin(width, height);
                try {
                    vesence$renderContent(r, width, height, alpha);
                } finally {
                    r.end();
                }
            }
        } catch (Exception e) {
            System.err.println("[weppos] SplashOverlay render error: " + e.getMessage());
        } finally {
            vesence.renderengine.MainTargetBinder.end(tempFbo);
            GlState.pop(snapshot);
        }
    }

    private void vesence$renderContent(Renderer2D r, int sw, int sh, float alpha) {
        float a = Mth.clamp(alpha, 0f, 1f);
        if (a <= 0.001f) return;

        r.pushAlpha(a);

        // Процедурный фон как в главном меню.
        r.rect(0, 0, sw, sh, 0, ColorUtil.getColor(7, 6, 11, 255));
        r.gradient(0, 0, sw, sh, 0,
                ColorUtil.getColor(15, 9, 16, 65), ColorUtil.getColor(8, 8, 13, 0),
                ColorUtil.getColor(5, 8, 13, 35), ColorUtil.getColor(12, 7, 14, 45));
        r.rect(0, 0, sw, sh, 0, ColorUtil.getColor(0, 110));

        float cx = sw / 2f;
        float cy = sh / 2f - sh * 0.03f;
        float scale = sh / 1080f;
        float ringR = 40f * scale;
        float ringThickness = 3.5f * scale;

        // Круговой индикатор загрузки: бледное кольцо + бегущая дуга.
        r.circleOutline(cx, cy, ringR, 0f, 1f, ColorUtil.getColor(237, 77, 104, 45), ringThickness);
        r.circleOutline(cx, cy, ringR, -vesence$spin, 0.28f, ColorUtil.getColor(237, 77, 104, 255), ringThickness);

        int pct = Math.round(Mth.clamp(this.currentProgress, 0f, 1f) * 100f);
        r.textCenter(FontRegistry.SF_SEMI, cx, cy + ringR + 42f * scale, 40f * scale,
                pct + "%", new int[]{ColorUtil.getColor(255, 255), ColorUtil.getColor(248, 247, 250, 255)});

        r.popAlpha();
    }
}
