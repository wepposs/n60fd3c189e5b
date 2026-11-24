package vesence.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vesence.module.impl.misc.FrameSync;

/**
 * FrameSync: пропуск целого кадра сверх лимита.
 *
 * <p>В 26.3 весь конвейер кадра (extract, render, blit, present, limitDisplayFPS) живёт
 * в {@code Minecraft#renderFrame(boolean)}. Это прямой аналог
 * {@code GameRenderer#render(RenderTickCounter, boolean)} из 1.21.4, который отменял
 * оригинальный мод. Отменяя {@code renderFrame} целиком, мы пропускаем и сбор данных,
 * и GPU-отрисовку, и present, и sleep лимитера — ровно как в оригинале.
 */
@Environment(EnvType.CLIENT)
@Mixin(Minecraft.class)
public class FrameSyncGameRendererMixin {
   @Inject(method = "renderFrame", at = @At("HEAD"), cancellable = true, require = 0)
   private void vesence$frameSync(boolean advanceGameTime, CallbackInfo ci) {
      FrameSync m = FrameSync.getInstance();
      if (m != null && !m.shouldRender()) {
         ci.cancel();
      }
   }
}
