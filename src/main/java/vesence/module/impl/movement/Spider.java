package vesence.module.impl.movement;

import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import vesence.Vesence;
import vesence.event.EventInit;
import vesence.event.impl.EventUpdate;
import vesence.event.player.EventMotion;
import vesence.module.api.Category;
import vesence.module.api.IModule;
import vesence.module.api.Module;
import vesence.module.api.setting.Setting;
import vesence.module.api.setting.impl.ModeSetting;
import vesence.module.api.setting.impl.SliderSetting;
import vesence.module.api.setting.impl.StringSetting;
import vesence.utils.player.InventoryActionUtil;

import java.util.Locale;

/**
 * WallClimb / Funtime Custom Block — точный перенос режима {@code dY} из NurikSRC.
 *
 * <p>Оригинал не просто ставит блок под собой. Он:
 * <ul>
 *   <li>сканирует соседние блоки на уровне ног и на один блок выше;</li>
 *   <li>целится в конкретную грань стены, а не в arbitrary точку рядом;</li>
 *   <li>проверяет, что позиция установки пересекает хитбокс игрока (блок ставится
 *       «в себя»);</li>
 *   <li>меняет слоты местами <b>до и после</b> {@code useItemOn} и отправляет
 *       «пустой» клик по слоту {@code -999};</li>
 *   <li>в следующем motion-событии выставляет {@code velocity.y = 0.42} и
 *       {@code onGround = true}.</li>
 * </ul>
 *
 * <p>Слот хранения берётся из фактического слота предмета. В оригинале там
 * захардкожен слот 25, из-за чего модуль работал только если предмет лежал
 * именно в нём. Здесь та же последовательность свапов работает из любого слота.
 */
@IModule(
      name = "Spider",
      description = "Автоматически поднимается по стенам, ставя выбранный предмет",
      category = Category.MOVEMENT,
      bind = -1
)
@Environment(EnvType.CLIENT)
public class Spider extends Module {

   /** Оставлен один режим — кастомный блок, как в оригинале. */
   public static final ModeSetting mode = new ModeSetting(
         "Режим", "Funtime Custom Block", "Funtime Custom Block"
   );

   /** Registry id выбранного предмета. Скрыт из GUI, но сохраняется в конфиге. */
   public static final StringSetting selectedItem = new StringSetting("Предмет", "").hidden(() -> true);

   /** Вертикальная скорость подъёма (оригинал 0.42). */
   public static final SliderSetting climbSpeed = new SliderSetting("Скорость подъёма", 0.42, 0.20, 0.60, 0.01);

   /** Задержка между постановками в тиках. В оригинале после постановки таймер = 1. */
   public static final SliderSetting placeDelay = new SliderSetting("Задержка постановки", 1.0, 0.0, 5.0, 1.0);

   /** Дальность луча из оригинала: eye + look * 3.0. */
   private static final double REACH = 3.0D;

   /** Оригинал сканирует layer от -1 (блок над ногами) до 0 (уровень ног). */
   private static final int LAYER_FROM = -1;
   private static final int LAYER_TO = 0;

   /** Внутренний шаг по Y: local14 / 2.0, local14 = 1..0. */
   private static final double STEP_DIVISOR = 2.0D;
   private static final double Y_BASE = 0.2D;

   private static final Direction[] HORIZONTAL = {
         Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
   };

   private int cooldown;
   private boolean jumpPending;

   public Spider() {
      this.addSettings(new Setting[]{mode, selectedItem, climbSpeed, placeDelay});
   }

   public static Spider getInstance() {
      if (Vesence.get == null || Vesence.get.manager == null) {
         return null;
      }
      Module module = Vesence.get.manager.getModule(Spider.class);
      return module instanceof Spider spider ? spider : null;
   }

   @Override
   public void onEnable() {
      super.onEnable();
      this.cooldown = 0;
      this.jumpPending = false;
   }

   @Override
   public void onDisable() {
      super.onDisable();
      this.cooldown = 0;
      this.jumpPending = false;
   }

   @EventInit
   public void onUpdate(EventUpdate event) {
      if (!this.enable || mc.player == null || mc.level == null || mc.gameMode == null) {
         return;
      }
      if (mc.player.isSpectator() || mc.player.isDeadOrDying()) {
         return;
      }
      // Оригинал всегда кликает по windowId = 0. Если открыт чужой контейнер,
      // слот 25 будет означать уже совсем другой слот.
      if (mc.player.containerMenu != mc.player.inventoryMenu) {
         return;
      }

      // timer--; if (timer < 0) — ровно как в dY.
      this.cooldown--;
      if (this.cooldown >= 0) {
         return;
      }

      Item item = getSelectedItem();
      if (item == null) {
         return;
      }

      int slot = this.findItemSlot(item);
      if (slot < 0) {
         return;
      }

      Placement placement = this.findPlacement();
      if (placement == null) {
         return;
      }

      // В оригинале rotation manager получает лучший угол и useItemOn идёт
      // сразу за ним. Никакого многотickового «доезда» нет.
      mc.player.setYRot(Mth.wrapDegrees(placement.yaw));
      mc.player.setXRot(Mth.clamp(placement.pitch, -90.0F, 90.0F));
      this.flushRotation();

      int containerId = mc.player.containerMenu.containerId;
      int hotbar = mc.player.getInventory().getSelectedSlot();
      int screenSlot = InventoryActionUtil.toScreenSlot(slot);

      // handleContainerInput(containerId, slot25, hotbar, SWAP, player)
      mc.gameMode.handleContainerInput(containerId, screenSlot, hotbar, ContainerInput.SWAP, mc.player);

      // useItemOn(player, level, MAIN_HAND, hit)
      mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, placement.hit);

      // handleContainerInput(containerId, slot25, hotbar, SWAP, player)
      mc.gameMode.handleContainerInput(containerId, screenSlot, hotbar, ContainerInput.SWAP, mc.player);

      // ClickWindow(0, -999, 0, PICKUP, emptyStack, nextTransactionId)
      this.sendOutsideClick();

      mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);

      this.jumpPending = true;
      this.cooldown = (int) Math.round(placeDelay.get().doubleValue());
   }

   @EventInit
   public void onMotion(EventMotion event) {
      if (!this.enable || !this.jumpPending || mc.player == null) {
         return;
      }

      Vec3 velocity = mc.player.getDeltaMovement();
      mc.player.setDeltaMovement(velocity.x, climbSpeed.get().doubleValue(), velocity.z);
      mc.player.syncVelocity = true;

      // Ki.m_static(true) + Ki.m_super(false): onGround = true, sprinting = false.
      event.setOnGround(true);
      mc.player.setSprinting(false);

      this.jumpPending = false;
   }

   // ─────────────────────────────────────────────────────────────────────────
   //  Поиск постановки (порт dY.m_super(Object))
   // ─────────────────────────────────────────────────────────────────────────

   private Placement findPlacement() {
      BlockPos playerPos = mc.player.blockPosition();
      AABB playerBox = mc.player.getBoundingBox();
      Vec3 eye = mc.player.getEyePosition();
      float currentYaw = mc.player.getYRot();
      float currentPitch = mc.player.getXRot();

      Placement best = null;
      double bestDiff = Double.MAX_VALUE;

      for (int layer = LAYER_FROM; layer <= LAYER_TO; layer++) {
         BlockPos base = playerPos.below(layer);

         for (Direction direction : HORIZONTAL) {
            BlockPos wallPos = base.relative(direction);
            BlockState state = mc.level.getBlockState(wallPos);
            if (state.isAir()) {
               continue;
            }

            VoxelShape shape = state.getCollisionShape(mc.level, wallPos);
            if (shape.isEmpty()) {
               continue;
            }

            AABB bounds = shape.bounds().move(wallPos);

            // local14 = 1, 0. Первый валидный по Y побеждает.
            for (int i = 1; i >= 0; i--) {
               double targetY = mc.player.getY() - layer + i / STEP_DIVISOR + Y_BASE;
               Vec3 target = new Vec3(
                     Mth.clamp(mc.player.getX(), bounds.minX, bounds.maxX),
                     Mth.clamp(targetY, bounds.minY, bounds.maxY),
                     Mth.clamp(mc.player.getZ(), bounds.minZ, bounds.maxZ)
               );

               float[] rotation = rotationTo(eye, target);
               BlockHitResult hit = raycast(rotation[0], rotation[1], wallPos, shape);

               if (!isPlacementInsidePlayer(hit, playerBox)) {
                  continue;
               }

               float yaw = rotation[0];
               float pitch = rotation[1];
               double diff = Math.abs(Mth.wrapDegrees(yaw - currentYaw));

               // Если текущий угол уже даёт валидную постановку — не крутимся.
               BlockHitResult currentHit = raycast(currentYaw, currentPitch, wallPos, shape);
               if (isPlacementInsidePlayer(currentHit, playerBox)) {
                  hit = currentHit;
                  yaw = currentYaw;
                  pitch = currentPitch;
                  diff = 0.0D;
               } else if (sameHit(currentHit, hit)) {
                  hit = currentHit;
                  yaw = currentYaw;
                  pitch = currentPitch;
                  diff = 0.0D;
               }

               if (diff < bestDiff) {
                  bestDiff = diff;
                  best = new Placement(hit, yaw, pitch);
               }
               break;
            }
         }
      }

      return best;
   }

   private BlockHitResult raycast(float yaw, float pitch, BlockPos pos, VoxelShape shape) {
      Vec3 eye = mc.player.getEyePosition();
      Vec3 look = Vec3.directionFromRotation(pitch, yaw);
      Vec3 end = eye.add(look.scale(REACH));

      BlockHitResult hit = shape.clip(eye, end, pos);
      // Оригинал: если луч не задел форму, возвращается hit по верхней грани
      // самого блока. Это не «промах», а валидный вариант постановки сверху.
      return hit != null ? hit : new BlockHitResult(eye, Direction.UP, pos, false);
   }

   private static boolean isPlacementInsidePlayer(BlockHitResult hit, AABB playerBox) {
      if (hit == null) {
         return false;
      }
      BlockPos placePos = hit.getBlockPos().relative(hit.getDirection());
      return new AABB(placePos).intersects(playerBox);
   }

   private static boolean sameHit(BlockHitResult first, BlockHitResult second) {
      if (first == null || second == null) {
         return false;
      }
      return first.getType() == second.getType()
            && first.getDirection() == second.getDirection()
            && first.getBlockPos().equals(second.getBlockPos());
   }

   private static float[] rotationTo(Vec3 from, Vec3 to) {
      double dx = to.x - from.x;
      double dy = to.y - from.y;
      double dz = to.z - from.z;
      double horizontal = Math.sqrt(dx * dx + dz * dz);

      float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
      float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
      return new float[]{Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0F, 90.0F)};
   }

   // ─────────────────────────────────────────────────────────────────────────
   //  Слоты / пакеты
   // ─────────────────────────────────────────────────────────────────────────

   /** Ищет предмет во всём инвентаре 0..35, как sg.m_while(OqQ). */
   private int findItemSlot(Item item) {
      for (int i = 0; i < 36; i++) {
         ItemStack stack = mc.player.getInventory().getItem(i);
         if (stack.is(item)) {
            return i;
         }
      }
      return -1;
   }

   /** Флаш углов на сервер перед пакетом действия. */
   private void flushRotation() {
      try {
         mc.player.sendChanges();
      } catch (Throwable ignored) {
      }
   }

   /**
    * ClickWindow(containerId, -999, button 0, PICKUP, emptyStack).
    * В 1.16 это был «сырой» пакет с nextTransactionId. В 26.3 ручной пакет
    * собирается с пустым cursor и пустой картой изменённых слотов.
    */
   private void sendOutsideClick() {
      if (mc.getConnection() == null || mc.player == null) {
         return;
      }
      mc.getConnection().send(new ServerboundContainerClickPacket(
            mc.player.containerMenu.containerId,
            mc.player.containerMenu.getStateId(),
            (short) -999,
            (byte) 0,
            ContainerInput.PICKUP,
            Int2ObjectMaps.emptyMap(),
            HashedStack.EMPTY
      ));
   }

   // ─────────────────────────────────────────────────────────────────────────
   //  Предмет (используется командой .wallclimb)
   // ─────────────────────────────────────────────────────────────────────────

   public static Item getSelectedItem() {
      return resolveItem(selectedItem.get());
   }

   public static Item resolveItem(String value) {
      if (value == null || value.isBlank()) {
         return null;
      }

      String token = value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
      Identifier id = token.indexOf(':') >= 0
            ? Identifier.tryParse(token)
            : Identifier.tryParse("minecraft:" + token);
      if (id == null) {
         return null;
      }

      Item item = BuiltInRegistries.ITEM.get(id).map(Holder.Reference::value).orElse(Items.AIR);
      return item == Items.AIR ? null : item;
   }

   public static String selectedItemId() {
      return selectedItem.get() == null ? "" : selectedItem.get();
   }

   /** Сохраняет id предмета; возвращает true, если предмет существует. */
   public static boolean storeItem(String value) {
      Item item = resolveItem(value);
      if (item == null) {
         return false;
      }
      Identifier id = BuiltInRegistries.ITEM.getKey(item);
      selectedItem.set(id == null ? "" : id.toString());
      return true;
   }

   private record Placement(BlockHitResult hit, float yaw, float pitch) {
   }
}
