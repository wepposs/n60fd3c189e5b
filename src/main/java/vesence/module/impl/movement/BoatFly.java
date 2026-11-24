package vesence.module.impl.movement;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import vesence.event.EventInit;
import vesence.event.impl.EventPacket;
import vesence.event.impl.EventUpdate;
import vesence.utils.network.NetworkUtils;
import vesence.module.api.Category;
import vesence.module.api.IModule;
import vesence.module.api.Module;
import vesence.module.api.setting.Setting;
import vesence.module.api.setting.impl.BooleanSetting;
import vesence.module.api.setting.impl.SliderSetting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@IModule(
        name = "BoatFly",
        description = "полет на лодке",
        category = Category.MOVEMENT,
        bind = -1
)
@Environment(EnvType.CLIENT)
public class BoatFly extends Module {

    private final SliderSetting speed = new SliderSetting("Скорость", 0.5, 0.1, 1.5, 0.05);
    private final SliderSetting vertical = new SliderSetting("Скорость вверх/вниз", 0.4, 0.1, 1.0, 0.05);
    private final SliderSetting turnSpeed = new SliderSetting("Скорость поворота", 2.0, 0.5, 6.0, 0.5);

    /*
     * Раз в N тиков чуть проседаем вниз: dY < -0.03125 обнуляет
     * серверный aboveGroundVehicleTickCount — ванильный флоат-кик не тикает.
     */
    private final SliderSetting descendEvery = new SliderSetting("Просадка каждые", 40, 10, 100, 5);

    /*
     * Античит-профиль (wepAC BoatFlyCheck): серверная лодка всегда в движении,
     * на подъёме держит горизонтальную скорость, периодически «падает»
     * баллистической дугой и не превышает лимиты поверхности.
     */
    private final BooleanSetting antiCheat = new BooleanSetting("Античит-профиль", true);

    private final BooleanSetting debug = new BooleanSetting("Отладка", false);

    private int tickCounter;
    private int setbackCount;
    private int debugTickCounter;

    /*
     * Grace-тики после догона: сервер ещё может дослать коррекцию
     * по старой позиции — глушим её полсекунды.
     */
    private int graceTicks;
    private static final int GRACE_LIMIT = 10;

    /*
     * ===== СЕРВЕРНАЯ ЛОДКА (ghost) =====
     *
     * Сервер в handleMoveVehicle двигает СВОЮ лодку через
     * Entity.move(PLAYER, delta) с коллизиями. Если его лодка упирается
     * в стену — moved wrongly → REJECT + absSnapTo назад + коррекция.
     * Сервер НИКОГДА не примет позицию внутри блока и прыжок сквозь блок.
     *
     * Поэтому клиентская лодка летит сквозь стены сама (noPhysics),
     * а наружу уходят координаты ghost — точки на ЛЕГАЛЬНОМ свободном
     * пути. Ghost идёт к цели через проёмы (BFS по свободным ячейкам),
     * каждый его шаг проверяется той же collision-функцией, что и на
     * сервере (Entity.collideBoundingBox): шаг, который сервер не
     * повторит без остатка, наружу не отправляется.
     *
     * wepAC (Bukkit) видит VehicleMoveEvent — то есть ИМЕННО ghost.
     * Его BoatFlyCheck флагает: зависание (dy > -0.04, h < 0.5, 6 событий),
     * вертикальный подъём (dy > 0.05, h < 0.6, 4 события) и превышение
     * скорости поверхности (земля 0.35 / вода 0.55 / лёд 1.8, 8 событий).
     * Античит-профиль держит ghost в рамках этих сигналов: непрерывный дрейф,
     * диагональный подъём, периодические баллистические просадки.
     */
    private Vec3 ghostPos;
    private Vec3 lastSent;
    private boolean desynced;

    /* BFS-маршрут ghost к реальной лодке (список точек-вех). */
    private final List<Vec3> route = new ArrayList<>();
    private Vec3 routeGoal;
    private int routeAge;

    /*
     * Ghost не должен отставать дальше этого — иначе сработает ванильный
     * speed-чек (dist^2 - vel^2 > 100) и рассинхрон станет неуправляемым.
     */
    private static final double MAX_GAP = 7.0D;

    /* Радиус поиска проёма вокруг прямой линии ghost→лодка. */
    private static final int ROUTE_RADIUS = 7;

    /* Античит-профиль: фаза цикла и параметры баллистической просадки. */
    private int acPhase;
    private int acCycle = 70;
    private double acDriftAngle;

    public BoatFly() {
        this.addSettings(new Setting[]{speed, vertical, turnSpeed, descendEvery, antiCheat, debug});
    }

    public static BoatFly getInstance() {
        return (BoatFly) vesence.Vesence.get.manager.getModule(BoatFly.class);
    }

    /*
     * W/S — тяга по носу, A/D — поворот, пробел — вверх, шифт — вниз.
     */
    @EventInit
    public void onUpdate(EventUpdate event) {
        if (!this.enable || mc.player == null || mc.level == null) {
            return;
        }

        Entity vehicle = mc.player.getVehicle();
        if (!(vehicle instanceof AbstractBoat boat)) {
            return;
        }

        tickCounter++;

        // поворот носа
        float yaw = boat.getYRot();
        if (mc.options.keyLeft.isDown()) yaw -= turnSpeed.get().floatValue();
        if (mc.options.keyRight.isDown()) yaw += turnSpeed.get().floatValue();
        if (mc.options.keyLeft.isDown() || mc.options.keyRight.isDown()) {
            boat.setYRot(yaw);
        }

        boolean fwd = mc.options.keyUp.isDown();
        boolean back = mc.options.keyDown.isDown();
        double thrust = (fwd ? 1.0 : 0.0) - (back ? 1.0 : 0.0);

        double yawRad = Math.toRadians(boat.getYRot());
        double targetX = -Math.sin(yawRad) * thrust * speed.get();
        double targetZ = Math.cos(yawRad) * thrust * speed.get();

        double targetY;
        if (mc.options.keyJump.isDown()) {
            targetY = vertical.get();
        } else if (mc.options.keyShift.isDown()) {
            targetY = -vertical.get();
        } else {
            targetY = 0.0;
        }

        // анти-флоат просадка — сбрасывает серверный счётчик «лодка висит в воздухе»
        if (tickCounter % descendEvery.get().intValue() == 0) {
            targetY = -0.04;
        }

        // сглаживание к целевой скорости — без рывков
        Vec3 vel = boat.getDeltaMovement();
        double smooth = 0.35D;
        boat.setDeltaMovement(
                vel.x + (targetX - vel.x) * smooth,
                vel.y + (targetY - vel.y) * smooth,
                vel.z + (targetZ - vel.z) * smooth
        );

        /*
         * Noclip: noPhysics — публичное поле. Включён, пока есть управление
         * или лодка в блоках: входит в блок в любом направлении и держит
         * noclip до выхода. Отпустил клавиши и вне блоков — коллизии возвращаются.
         */
        boolean phasing = this.isPhasing(boat);
        boolean hasInput = thrust != 0.0D
                || mc.options.keyJump.isDown()
                || mc.options.keyShift.isDown();
        boat.noPhysics = hasInput || phasing;

        this.updateGhost(boat);

        if (this.desynced) {
            graceTicks = GRACE_LIMIT;
        } else if (graceTicks > 0) {
            graceTicks--;
        }

        this.debugTick(boat);
    }

    /*
     * Исходящие: shift=false в ServerboundPlayerInputPacket (иначе сервер
     * выпустит из лодки). Пока ghost != реальной позиции, наружу летят
     * координаты ghost — реальные сервер отверг бы и прислал коррекцию.
     *
     * Входящие: коррекции лодки глушим при рассинхроне/grace.
     * ClientboundPlayerPositionPacket НЕ трогаем — ванильный обработчик
     * шлёт ACK, без него серверный updateAwaitingTeleport залипает.
     */
    @EventInit
    public void onPacket(EventPacket event) {
        if (!this.enable || mc.player == null) {
            return;
        }

        if (event.isSend()) {
            if (event.getPacket() instanceof ServerboundPlayerInputPacket inputPacket
                    && mc.player.getVehicle() instanceof AbstractBoat) {
                Input in = inputPacket.input();
                if (in.shift()) {
                    // HEAD-инжект не заменяет аргумент: отменяем и шлём чистую копию
                    event.cancel();
                    NetworkUtils.sendSilentPacket(new ServerboundPlayerInputPacket(
                            new Input(in.forward(), in.backward(), in.left(), in.right(),
                                    in.jump(), false, in.sprint())));
                }
                return;
            }

            if (event.getPacket() instanceof ServerboundMoveVehiclePacket
                    && mc.player.getVehicle() instanceof AbstractBoat boat) {
                if (this.desynced) {
                    event.cancel();
                    Vec3 ghost = this.ghostPosition(boat);
                    this.lastSent = ghost;
                    NetworkUtils.sendSilentPacket(new ServerboundMoveVehiclePacket(
                            PositionAndRotation.of(ghost, boat.getYRot(), boat.getXRot()),
                            false));
                } else {
                    // синхронный полёт: сервер видит честную позицию
                    this.lastSent = boat.position();
                }
            }
            return;
        }

        if (event.getPacket() instanceof ClientboundMoveVehiclePacket) {
            if (this.desynced || this.graceTicks > 0) {
                event.cancel();
                return;
            }
            this.boat_setback();
        } else if (event.getPacket() instanceof ClientboundTeleportEntityPacket tpPacket) {
            // сервер телепортирует лодку — та же коррекция
            if (mc.player.getVehicle() instanceof AbstractBoat boat
                    && tpPacket.id() == boat.getId()) {
                if (this.desynced || this.graceTicks > 0) {
                    event.cancel();
                    return;
                }
                this.boat_setback();
            }
        } else if (event.getPacket() instanceof ClientboundEntityPositionSyncPacket syncPacket) {
            // 26.3: полный sync позиции лодки вместо дельта-пакета
            if (mc.player.getVehicle() instanceof AbstractBoat boat
                    && syncPacket.id() == boat.getId()) {
                if (this.desynced || this.graceTicks > 0) {
                    event.cancel();
                    return;
                }
                this.boat_setback();
            }
        } else if (event.getPacket() instanceof ClientboundSetEntityMotionPacket motionPacket) {
            // серверная физика лодки (floatBoat) шлёт её скорость — не даём
            // ей перетереть нашу во время рассинхрона
            if (mc.player.getVehicle() instanceof AbstractBoat boat
                    && motionPacket.id() == boat.getId()
                    && (this.desynced || this.graceTicks > 0)) {
                event.cancel();
            }
        } else if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            // не глушим: ванильный обработчик сам шлёт ACK с нужным id
            this.setbackCount++;
        }
    }

    private void boat_setback() {
        if (mc.player.getVehicle() != null) {
            mc.player.getVehicle().setDeltaMovement(Vec3.ZERO);
        }
        tickCounter = 0;
        setbackCount++;
    }

    public boolean isFlying(Entity vehicle) {
        return this.enable
                && mc.player != null
                && mc.player.getVehicle() == vehicle
                && vehicle instanceof AbstractBoat;
    }

    /*
     * Фейзинг: AABB лодки пересекает блоки — клиент сейчас внутри стены.
     */
    public boolean isPhasing(AbstractBoat boat) {
        return mc.level != null && !mc.level.noCollision(boat, boat.getBoundingBox());
    }

    // ==================================================================
    // Ghost: серверная фикция на легальном пути
    // ==================================================================

    private void updateGhost(AbstractBoat boat) {
        Vec3 real = boat.position();

        if (ghostPos == null) {
            ghostPos = real;
            lastSent = real;
        }

        double gap = ghostPos.distanceTo(real);

        // догнали — рассинхрона нет, пакеты снова честные
        if (gap < 0.05D && this.isFree(boat, real)) {
            ghostPos = real;
            desynced = false;
            route.clear();
            return;
        }

        // отстаём слишком далеко — телепортируем фикцию к лодке, если точка
        // свободна; иначе продолжаем маршрут (ванна speed-чек иначе кикает)
        if (gap > MAX_GAP && this.isFree(boat, real)) {
            ghostPos = real;
            desynced = false;
            route.clear();
            return;
        }

        desynced = true;

        // цель маршрута сместилась — перестраиваем (не чаще раза в 5 тиков)
        if (route.isEmpty() || routeGoal == null || routeGoal.distanceTo(real) > 1.5D || routeAge > 20) {
            this.buildRoute(boat, ghostPos, real);
            routeGoal = real;
            routeAge = 0;
        }
        routeAge++;

        // призрак всегда движется: статичный ghost = hover-сигнал wepAC
        Vec3 target = route.isEmpty() ? real : route.get(0);
        Vec3 step = this.legalStep(boat, ghostPos, target);

        if (step == null) {
            // прямой путь заблокирован и маршрут пуст — ищем любой свободный шаг
            step = this.freeDrift(boat, ghostPos, real);
        }

        if (step != null) {
            ghostPos = step;
            if (!route.isEmpty() && ghostPos.distanceTo(route.get(0)) < 0.4D) {
                route.remove(0);
            }
        }

        if (antiCheat.get()) {
            ghostPos = this.applyAcProfile(boat, ghostPos, real);
        }
    }

    /*
     * Шаг, который сервер повторит БЕЗ остатка: та же функция коллизий,
     * что в Entity.move. Если результат != желаемому — шаг блокирован.
     */
    private Vec3 legalStep(AbstractBoat boat, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double len = delta.length();
        if (len < 1.0E-6D) {
            return null;
        }
        double max = this.surfaceCap(boat, from);
        if (len > max) {
            delta = delta.scale(max / len);
        }
        AABB box = boat.getBoundingBox().move(
                from.x - boat.getX(), from.y - boat.getY(), from.z - boat.getZ());
        Vec3 adjusted = Entity.collideBoundingBox(boat, delta, box, mc.level,
                mc.level.getEntityCollisions(boat, box.expandTowards(delta)));
        // сервер сдвинул меньше, чем просили — упёрлись, шаг не годится
        if (adjusted.lengthSqr() < delta.lengthSqr() * 0.98D) {
            return null;
        }
        return from.add(adjusted);
    }

    /*
     * Лимит горизонтали по поверхности (wepAC speed caps с запасом):
     * лёд 1.8, вода 0.55, земля/воздух 0.35.
     */
    private double surfaceCap(AbstractBoat boat, Vec3 pos) {
        if (!antiCheat.get()) {
            return 1.5D;
        }
        net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(pos.x, pos.y - 0.3D, pos.z);
        net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(below);
        String name = state.getBlock().getDescriptionId();
        if (name.contains("ice")) {
            return 1.7D;
        }
        if (!state.getFluidState().isEmpty()) {
            return 0.5D;
        }
        return 0.34D;
    }

    /*
     * BFS по свободным ячейкам (1 блок) от ghost к лодке: ищет проём —
     * дверь, окно, дыру в стене — вместо тупого подъёма над крышей.
     * Возвращает список центров ячеек; прямая видимость не требуется.
     */
    private void buildRoute(AbstractBoat boat, Vec3 from, Vec3 to) {
        route.clear();
        if (mc.level == null) {
            return;
        }

        int sx = (int) Math.floor(from.x), sy = (int) Math.floor(from.y), sz = (int) Math.floor(from.z);
        int tx = (int) Math.floor(to.x), ty = (int) Math.floor(to.y), tz = (int) Math.floor(to.z);

        // ячеек немного: коридор вокруг прямой + радиус поиска проёма
        int minX = Math.min(sx, tx) - ROUTE_RADIUS, maxX = Math.max(sx, tx) + ROUTE_RADIUS;
        int minY = Math.min(sy, ty) - 2, maxY = Math.max(sy, ty) + ROUTE_RADIUS;
        int minZ = Math.min(sz, tz) - ROUTE_RADIUS, maxZ = Math.max(sz, tz) + ROUTE_RADIUS;

        java.util.Map<Long, Long> parent = new java.util.HashMap<>();
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        long start = key(sx, sy, sz);
        long goal = key(tx, ty, tz);
        queue.add(start);
        parent.put(start, start);

        int expanded = 0;
        boolean found = false;
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

        while (!queue.isEmpty() && expanded < 4000) {
            long cur = queue.poll();
            expanded++;
            if (cur == goal) {
                found = true;
                break;
            }
            int cx = kx(cur), cy = ky(cur), cz = kz(cur);
            for (int[] d : dirs) {
                int nx = cx + d[0], ny = cy + d[1], nz = cz + d[2];
                if (nx < minX || nx > maxX || ny < minY || ny > maxY || nz < minZ || nz > maxZ) {
                    continue;
                }
                long nk = key(nx, ny, nz);
                if (parent.containsKey(nk)) {
                    continue;
                }
                // ячейка свободна для AABB лодки (центр ячейки + 0.5)
                if (!this.isFree(boat, new Vec3(nx + 0.5D, ny + 0.1D, nz + 0.5D))) {
                    continue;
                }
                parent.put(nk, cur);
                queue.add(nk);
            }
        }

        if (!found) {
            return; // проёма нет — ghost пойдёт по свободному дрейфу/подъёму
        }

        // восстанавливаем путь и упрощаем: оставляем точки поворота
        List<Vec3> raw = new ArrayList<>();
        long cur = goal;
        while (cur != start) {
            raw.add(new Vec3(kx(cur) + 0.5D, ky(cur) + 0.1D, kz(cur) + 0.5D));
            cur = parent.get(cur);
        }
        Collections.reverse(raw);

        // сглаживание: выбрасываем промежуточные точки на прямых участках
        Vec3 anchor = from;
        for (int i = 0; i < raw.size(); i++) {
            boolean isLast = i == raw.size() - 1;
            boolean turn = isLast;
            if (!isLast) {
                Vec3 a = raw.get(i).subtract(anchor);
                Vec3 b = raw.get(i + 1).subtract(raw.get(i));
                turn = a.normalize().dot(b.normalize()) < 0.99D;
            }
            if (turn) {
                route.add(raw.get(i));
                anchor = raw.get(i);
            }
        }
        route.add(to);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static int kx(long k) {
        int v = (int) (k >> 38);
        return (v << 6) >> 6; // sign-extend 26 bit
    }

    private static int ky(long k) {
        int v = (int) ((k >> 26) & 0xFFF);
        return (v << 20) >> 20; // sign-extend 12 bit
    }

    private static int kz(long k) {
        int v = (int) (k & 0x3FFFFFF);
        return (v << 6) >> 6;
    }

    /*
     * Свободный шаг, когда маршрут не найден: диагональ к цели, подъём
     * с горизонтальным дрейфом (анти-rise), либо баллистическое падение.
     */
    private Vec3 freeDrift(AbstractBoat boat, Vec3 from, Vec3 real) {
        Vec3 toReal = real.subtract(from);
        double cap = this.surfaceCap(boat, from);

        // 1) горизонтальная составляющая к цели
        Vec3 h = new Vec3(toReal.x, 0.0D, toReal.z);
        if (h.lengthSqr() > 1.0E-6D) {
            Vec3 step = this.legalStep(boat, from, from.add(h.normalize().scale(cap)));
            if (step != null) return step;
        }

        // 2) диагональный подъём: dy <= 0.05 при h >= 0.3 — rise-сигнал молчит
        if (toReal.y > 0.0D) {
            Vec3 diag = new Vec3(toReal.x, 0.0D, toReal.z);
            if (diag.lengthSqr() < 1.0E-6D) {
                diag = new Vec3(Math.cos(acDriftAngle), 0.0D, Math.sin(acDriftAngle));
            }
            diag = diag.normalize().scale(0.3D);
            Vec3 step = this.legalStep(boat, from, from.add(diag.x, 0.05D, diag.z));
            if (step != null) return step;
        }

        // 3) вниз к цели
        if (toReal.y < 0.0D) {
            Vec3 step = this.legalStep(boat, from, from.add(0.0D, -Math.min(cap, -toReal.y), 0.0D));
            if (step != null) return step;
        }

        // 4) баллистическое падение — сбрасывает hover/rise streaks wepAC
        Vec3 fall = this.legalStep(boat, from, from.add(
                Math.cos(acDriftAngle) * 0.2D, -0.3D, Math.sin(acDriftAngle) * 0.2D));
        if (fall != null) return fall;

        return null;
    }

    /*
     * Античит-профиль поверх маршрута: ghost никогда не замирает и
     * периодически «падает», как лодка, сорвавшаяся с волны.
     */
    private Vec3 applyAcProfile(AbstractBoat boat, Vec3 pos, Vec3 real) {
        acPhase++;
        if (acPhase >= acCycle) {
            acPhase = 0;
            acCycle = 60 + mc.level.getRandom().nextInt(40);
            acDriftAngle = mc.level.getRandom().nextDouble() * Math.PI * 2.0D;
        }

        Vec3 delta = pos.subtract(lastSent == null ? pos : lastSent);
        double hDist = Math.sqrt(delta.x * delta.x + delta.z * delta.z);

        // баллистическая просадка: 6 тиков из цикла ghost падает —
        // dy < -0.04 обнуляет hover-счётчик wepAC и ванильный флоат-счётчик
        if (acPhase >= acCycle - 6) {
            Vec3 fall = this.legalStep(boat, pos, pos.add(
                    Math.cos(acDriftAngle) * 0.25D, -0.25D, Math.sin(acDriftAngle) * 0.25D));
            if (fall != null) {
                return fall;
            }
        }

        // непрерывный дрейф: если ghost почти стоит — подталкиваем по свободной
        // горизонтали, чтобы hover-сигнал (h < 0.5 && dy > -0.04) не копился
        if (hDist < 0.08D) {
            Vec3 drift = this.legalStep(boat, pos, pos.add(
                    Math.cos(acDriftAngle) * 0.3D, 0.0D, Math.sin(acDriftAngle) * 0.3D));
            if (drift != null) {
                return drift;
            }
            // дрейф заблокирован — пробуем перпендикуляр
            Vec3 side = this.legalStep(boat, pos, pos.add(
                    Math.cos(acDriftAngle + Math.PI / 2) * 0.3D, 0.0D,
                    Math.sin(acDriftAngle + Math.PI / 2) * 0.3D));
            if (side != null) {
                return side;
            }
        }

        return pos;
    }

    /*
     * Свободна ли позиция для AABB лодки.
     */
    private boolean isFree(AbstractBoat boat, Vec3 pos) {
        if (mc.level == null) {
            return true;
        }
        AABB box = boat.getBoundingBox().move(
                pos.x - boat.getX(), pos.y - boat.getY(), pos.z - boat.getZ());
        return mc.level.noCollision(boat, box);
    }

    private Vec3 ghostPosition(AbstractBoat boat) {
        if (ghostPos == null) {
            ghostPos = boat.position();
        }
        return ghostPos;
    }

    private void debugTick(Entity vehicle) {
        if (!debug.get()) return;

        debugTickCounter++;
        if (debugTickCounter < 20) return;
        debugTickCounter = 0;

        Vec3 v = vehicle.getDeltaMovement();
        String phase = (vehicle instanceof AbstractBoat b && this.isPhasing(b)) ? "ФАЗА" : "-";
        String noclip = vehicle.noPhysics ? "ON" : "off";
        double gap = (vehicle instanceof AbstractBoat b2 && ghostPos != null)
                ? ghostPos.distanceTo(b2.position()) : 0.0D;
        this.debugMessage(String.format(
                "y=%.1f vy=%.3f h=%.3f ф=%s noclip=%s гр=%d кор=%d ghost=%.2f маршрут=%d%s",
                vehicle.getY(), v.y, Math.sqrt(v.x * v.x + v.z * v.z),
                phase, noclip, graceTicks, setbackCount, gap, route.size(),
                this.desynced ? " РАССИНХРОН" : ""
        ));
    }

    private void debugMessage(String message) {
        if (mc.player == null) return;
        try {
            mc.player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal("[BoatFly] " + message)
            );
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onEnable() {
        ghostPos = null;
        lastSent = null;
        desynced = false;
        graceTicks = 0;
        route.clear();
        routeGoal = null;
        acPhase = 0;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        /*
         * Выключение при рассинхроне: серверная лодка стоит в легальной
         * точке, клиентская — внутри стены. Возвращаем клиентскую лодку
         * к серверной позиции, чтобы после выключения не было отката
         * и лодка не застряла в блоке с включёнными коллизиями.
         */
        if (mc.player != null) {
            Entity vehicle = mc.player.getVehicle();
            if (vehicle instanceof AbstractBoat boat) {
                if (desynced && ghostPos != null) {
                    boat.absSnapTo(ghostPos.x, ghostPos.y, ghostPos.z,
                            boat.getYRot(), boat.getXRot());
                    boat.setDeltaMovement(Vec3.ZERO);
                    boat.noPhysics = false;
                    // пассажир следует за лодкой
                    boat.positionRider(mc.player);
                } else {
                    boat.noPhysics = false;
                }
            }
        }
        ghostPos = null;
        lastSent = null;
        desynced = false;
        graceTicks = 0;
        route.clear();
        routeGoal = null;
        super.onDisable();
    }
}