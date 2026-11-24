package vesence.framesync;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import vesence.module.impl.misc.FrameSync;

/** Порт org.framesync.client.FpsIndicator. */
@Environment(EnvType.CLIENT)
public final class FpsIndicator {
   private static long lastUpdateTime;
   private static int frameCount;
   private static double currentFps;

   private FpsIndicator() {
   }

   public static void updateFps() {
      frameCount++;
      long now = System.currentTimeMillis();
      if (now - lastUpdateTime >= 1000) {
         currentFps = frameCount * 1000.0 / (now - lastUpdateTime);
         frameCount = 0;
         lastUpdateTime = now;
      }
   }

   public static void render(GuiGraphicsExtractor g, FrameSync m) {
      if (!m.showFpsIndicator.get()) {
         return;
      }
      Font font = Minecraft.getInstance().font;
      int hz = m.effectiveHz();
      String text = String.format("FPS: %.1f / %d Hz", currentFps, hz);
      if (!m.enableRenderLimit.get()) {
         text += " (OFF)";
      }
      int color = 0xFF000000 | color(currentFps, hz);
      int x = g.guiWidth() - font.width(text) - 10;
      int y = 10;
      g.fill(x - 2, y - 2, x + font.width(text) + 2, y + font.lineHeight + 2, 0x80000000);
      g.text(font, text, x, y, color);
   }

   private static int color(double fps, int hz) {
      double ratio = fps / hz;
      return ratio >= 0.9 ? 0x00FF00 : ratio >= 0.7 ? 0xFFFF00 : 0xFF0000;
   }
}
