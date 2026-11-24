package vesence.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vesence.module.impl.movement.BoatFly;

/**
 * BoatFly: лодка локального игрока летает во все стороны и сквозь стены.
 *
 * <p>Сервер 26.3 в handleMoveVehicle проверяет (a) квадрат скорости из
 * пакета (лимит ~10 блоков/тик), (b) воспроизводит движение своей лодкой
 * через Entity.move(PLAYER, delta) с коллизиями и.reject'ит шаг с остатком
 * > 0.25 блока или новую коллизию AABB, (c) флоат-кик через счётчик тиков.
 * Модуль держит клиентскую лодку в noPhysics, а наружу шлёт координаты
 * ghost-точки на легальном свободном пути (BFS через проёмы); каждый шаг
 * ghost проверяется Entity.collideBoundingBox — той же функцией, что на
 * сервере. Входящие коррекции (MoveVehicle / TeleportEntity /
 * EntityPositionSync / SetEntityMotion) глушатся, пока ghost не догнал
 * реальную лодку. Античит-профиль (wepAC BoatFlyCheck) держит ghost в
 * движении с периодическими баллистическими просадками и диагональным
 * подъёмом — hover/rise/speed streaks не накапливаются.
 */
@Environment(EnvType.CLIENT)
@Mixin(AbstractBoat.class)
public abstract class BoatFlyMixin {

    @Unique
    private boolean vesence$boatFlyActive() {
        try {
            BoatFly boatFly = BoatFly.getInstance();
            return boatFly != null && boatFly.isFlying((Entity) (Object) this);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Гравитация и трение воды/земли выключены — вертикаль не дёргается
     * между ванильным floatBoat и нашим setDeltaMovement.
     */
    @Inject(method = "floatBoat", at = @At("HEAD"), cancellable = true)
    private void vesence$skipFloatBoat(CallbackInfo ci) {
        if (vesence$boatFlyActive()) {
            ci.cancel();
        }
    }

    /**
     * Ванильное управление лодкой (только вперёд + поворот с гравитацией
     * разгона) выключено: модуль задаёт скорость сам в EventUpdate.
     */
    @Inject(method = "controlBoat", at = @At("HEAD"), cancellable = true)
    private void vesence$skipControlBoat(CallbackInfo ci) {
        if (vesence$boatFlyActive()) {
            ci.cancel();
        }
    }

    /**
     * Noclip модуль выставляет сам в onUpdate (noPhysics — публичное поле,
     * миксин-посредник не обязателен и даже вреден: безусловный true здесь
     * перетирал бы расчёт «посадка = коллизии вкл»). Инжект в tick() на
     * noPhysics намеренно отсутствует.
     */
}
