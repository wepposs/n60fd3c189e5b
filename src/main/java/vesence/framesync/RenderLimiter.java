package vesence.framesync;

import com.mojang.blaze3d.platform.Monitor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

/** Порт org.framesync.client.RenderLimiter (FrameSync 1.21.4) 1:1. */
@Environment(EnvType.CLIENT)
public final class RenderLimiter {
   private static final int FRAME_HISTORY_SIZE = 10;

   private int monitorHz;
   private long lastRenderTime = System.nanoTime();
   private boolean enabled = true;
   private long frameInterval;
   private final long[] frameTimeHistory = new long[FRAME_HISTORY_SIZE];
   private int frameTimeIndex;
   private long accumulatedTime;
   private boolean lowEndMode;

   public RenderLimiter(int monitorHz, boolean lowEndMode) {
      this.monitorHz = monitorHz;
      this.lowEndMode = lowEndMode;
      this.frameInterval = 1_000_000_000L / monitorHz;
   }

   public boolean canRender() {
      if (!enabled) {
         return true;
      }
      return lowEndMode ? canRenderSmooth() : canRenderAggressive();
   }

   private boolean canRenderSmooth() {
      long now = System.nanoTime();
      long delta = now - lastRenderTime;
      accumulatedTime += delta;
      if (accumulatedTime >= frameInterval) {
         frameTimeHistory[frameTimeIndex] = delta;
         frameTimeIndex = (frameTimeIndex + 1) % FRAME_HISTORY_SIZE;
         accumulatedTime -= frameInterval;
         if (accumulatedTime > frameInterval * 2) {
            accumulatedTime = frameInterval;
         }
         lastRenderTime = now;
         return true;
      }
      if (delta > frameInterval * 3) {
         lastRenderTime = now;
         accumulatedTime = 0;
         return true;
      }
      return false;
   }

   private boolean canRenderAggressive() {
      long now = System.nanoTime();
      if (now - lastRenderTime >= frameInterval) {
         frameTimeHistory[frameTimeIndex] = now - lastRenderTime;
         frameTimeIndex = (frameTimeIndex + 1) % FRAME_HISTORY_SIZE;
         lastRenderTime = now;
         return true;
      }
      return false;
   }

   public void setMonitorHz(int hz) {
      this.monitorHz = hz > 0 ? hz : 60;
      this.frameInterval = 1_000_000_000L / this.monitorHz;
      this.lastRenderTime = System.nanoTime();
      this.accumulatedTime = 0;
      this.frameTimeIndex = 0;
   }

   public void setLowEndMode(boolean lowEndMode) {
      this.lowEndMode = lowEndMode;
   }

   public void setEnabled(boolean enabled) {
      this.enabled = enabled;
      if (enabled) {
         lastRenderTime = System.nanoTime();
         accumulatedTime = 0;
         frameTimeIndex = 0;
      }
   }

   /** Аналог ModConfig.safeDetectMonitorHz: 60 при любой ошибке. */
   public static int safeDetectMonitorHz() {
      try {
         Monitor monitor = Minecraft.getInstance().getWindow().findBestMonitor();
         int hz = monitor == null ? 0 : Math.round(monitor.currentMode().getRefreshRate());
         return hz > 0 ? hz : 60;
      } catch (Throwable t) {
         return 60;
      }
   }
}
