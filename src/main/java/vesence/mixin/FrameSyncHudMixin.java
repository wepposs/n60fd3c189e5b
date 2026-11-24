package vesence.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vesence.framesync.FpsIndicator;
import vesence.module.impl.misc.FrameSync;

/** FrameSync: индикатор FPS поверх HUD. */
@Environment(EnvType.CLIENT)
@Mixin(Hud.class)
public class FrameSyncHudMixin {
   @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
   private void vesence$fpsIndicator(GuiGraphicsExtractor g, DeltaTracker tracker, CallbackInfo ci) {
      FrameSync m = FrameSync.getInstance();
      if (m == null || !m.enable) {
         return;
      }
      FpsIndicator.updateFps();
      FpsIndicator.render(g, m);
   }
}
