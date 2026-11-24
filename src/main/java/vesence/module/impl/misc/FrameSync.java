package vesence.module.impl.misc;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import vesence.framesync.RenderLimiter;
import vesence.module.api.Category;
import vesence.module.api.IModule;
import vesence.module.api.Module;
import vesence.module.api.setting.Setting;
import vesence.module.api.setting.impl.BooleanSetting;
import vesence.module.api.setting.impl.SliderSetting;

/** Порт мода FrameSync 1.21.4: лимит рендера по частоте монитора. */
@IModule(name = "FrameSync", description = "Ограничивает FPS частотой монитора: меньше нагрузка и нагрев", category = Category.MISC, bind = -1)
@Environment(EnvType.CLIENT)
public class FrameSync extends Module {
   private static FrameSync instance;

   public final BooleanSetting enableRenderLimit = new BooleanSetting("Ограничение рендеринга", true);
   public final BooleanSetting autoDetect = new BooleanSetting("Автоопределение частоты монитора", true);
   public final SliderSetting targetHz = new SliderSetting("Частота монитора", 60, 1, 360, 1).hidden(() -> autoDetect.get());
   public final SliderSetting minFpsLimit = new SliderSetting("Минимальный FPS", 30, 1, 240, 1);
   public final BooleanSetting showFpsIndicator = new BooleanSetting("Индикатор FPS", false);
   public final BooleanSetting lowEndMode = new BooleanSetting("Режим для слабых ПК (плавно)", false);

   private RenderLimiter limiter;

   public FrameSync() {
      instance = this;
      this.addSettings(new Setting[]{enableRenderLimit, autoDetect, targetHz, minFpsLimit, showFpsIndicator, lowEndMode});
   }

   public static FrameSync getInstance() {
      return instance;
   }

   public int effectiveHz() {
      return autoDetect.get() ? RenderLimiter.safeDetectMonitorHz() : (int) Math.round(targetHz.get());
   }

   /** @return {@code true}, если кадр нужно рисовать (аналог RenderManager#shouldRender). */
   public boolean shouldRender() {
      if (!enable || !enableRenderLimit.get()) {
         limiter = null;
         return true;
      }
      int hz = Math.max(1, effectiveHz());
      if (limiter == null) {
         limiter = new RenderLimiter(hz, lowEndMode.get());
         lastHz = hz;
      } else if (hz != lastHz) {
         limiter.setMonitorHz(hz);
         lastHz = hz;
      }
      limiter.setLowEndMode(lowEndMode.get());
      return limiter.canRender();
   }

   private int lastHz = -1;

   @Override
   public void onDisable() {
      super.onDisable();
      limiter = null;
   }
}
