package vesence.module.impl.visuals.chinahat;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline.Snippet;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.model.HumanoidModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import com.mojang.math.Axis;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import vesence.module.impl.visuals.ChinaHat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;

/** China hat - 1:1 port of Minced ChinaHat (EventLayerHead, model space). */
public final class ChinaHatRenderer {

    private static final float OFFSET_BARE = 0.42F;
    private static final float OFFSET_HELMET = 0.50F;
    private static final float ROT_Z_DEGREES = 180.0F;
    private static final float ROT_Y_DEGREES = 90.0F;
    private static final int SEGMENTS = 128;
    private static final float FEATHER = 0.02F;
    private static final float CONE_APEX_HEIGHT = 0.3F;
    private static final float LIP_DEPTH = 0.025F;

    private static volatile RenderType coneLayer;
    private static volatile RenderType fringeLayer;

    private final ChinaHat parent;

    public ChinaHatRenderer(ChinaHat parent) {
        this.parent = parent;
    }

    public void renderOnHead(Player player, PoseStack matrices, HumanoidModel<?> model,
                             SubmitNodeCollector queue) {
        Minecraft client = Minecraft.getInstance();
        if (player == null || model == null || client.level == null || client.player == null) return;
        boolean local = player == client.player;
        boolean friend = vesence.utils.friends.FriendStorage.isFriend(player.getName().getString());
        if (!local && (!friend || !parent.showChinaHatOnFriends())) return;

        ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        float offset = helmet.isEmpty() ? OFFSET_BARE : OFFSET_HELMET;
        float width = (float) (player.getBoundingBox().maxX - player.getBoundingBox().minX);
        int theme = this.parent.resolveThemeColor();
        // Entity models are shared and their parts are mutated for every rendered entity.
        // submitCustom is executed later, so retaining ModelPart here makes the hat use
        // whichever head pose was written last and causes it to slide away from its owner.
        HeadTransform head = HeadTransform.capture(model.head);

        queue.submitCustomGeometry(matrices, coneLayer(), (entry, consumer) -> {
            applyHead(entry, head);
            entry.translate(0.0F, -offset, 0.0F);
            entry.rotate(Axis.ZN.rotationDegrees(ROT_Z_DEGREES));
            entry.rotate(Axis.YP.rotationDegrees(ROT_Y_DEGREES));
            triVertex = 0;
            drawCone(entry.pose(), consumer, theme, width);
        });

        queue.submitCustomGeometry(matrices, fringeLayer(), (entry, consumer) -> {
            applyHead(entry, head);
            entry.translate(0.0F, -offset, 0.0F);
            entry.rotate(Axis.ZN.rotationDegrees(ROT_Z_DEGREES));
            entry.rotate(Axis.YP.rotationDegrees(ROT_Y_DEGREES));
            triVertex = 0;
            drawFringe(entry.pose(), consumer, theme, width);
        });

    }

    /** Непрозрачный конус + небольшой бортик снизу, обычные треугольники (без strip — иначе батч склеивает шляпы разных игроков). */
    private static void drawCone(Matrix4f m, VertexConsumer c, int theme, float width) {
        int apex = shade(theme, 1.15F);
        int lip = shade(theme, 0.6F);
        int under = shade(theme, 0.45F);
        float step = Mth.TWO_PI / SEGMENTS;
        for (int i = 0; i < SEGMENTS; i++) {
            float a0 = i * step, a1 = (i + 1) * step;
            float x0 = Mth.sin(a0) * width, z0 = Mth.cos(a0) * width;
            float x1 = Mth.sin(a1) * width, z1 = Mth.cos(a1) * width;
            int c0 = shade(theme, 0.75F + 0.25F * Mth.cos(a0 - 0.8F));
            int c1 = shade(theme, 0.75F + 0.25F * Mth.cos(a1 - 0.8F));
            // верх конуса
            v(c, m, x0, 0.0F, z0, c0);
            v(c, m, x1, 0.0F, z1, c1);
            v(c, m, 0.0F, CONE_APEX_HEIGHT, 0.0F, apex);
            // бортик
            v(c, m, x0, 0.0F, z0, lip);
            v(c, m, x1, -LIP_DEPTH, z1, lip);
            v(c, m, x1, 0.0F, z1, lip);
            v(c, m, x0, 0.0F, z0, lip);
            v(c, m, x0, -LIP_DEPTH, z0, lip);
            v(c, m, x1, -LIP_DEPTH, z1, lip);
            // низ (тёмная изнанка)
            v(c, m, x0, -LIP_DEPTH, z0, under);
            v(c, m, 0.0F, CONE_APEX_HEIGHT * 0.5F, 0.0F, under);
            v(c, m, x1, -LIP_DEPTH, z1, under);
        }
    }

    /** Полупрозрачная кайма по краю полей: сглаживает «лесенку» без MSAA. Глубину не пишет. */
    private static void drawFringe(Matrix4f m, VertexConsumer c, int theme, float width) {
        float step = Mth.TWO_PI / SEGMENTS;
        float outer = width + FEATHER;
        for (int i = 0; i < SEGMENTS; i++) {
            float a0 = i * step, a1 = (i + 1) * step;
            int s0 = shade(theme, 0.6F), clear = s0 & 0x00FFFFFF;
            float sx0 = Mth.sin(a0), cz0 = Mth.cos(a0), sx1 = Mth.sin(a1), cz1 = Mth.cos(a1);
            for (float y : new float[]{0.0F, -LIP_DEPTH}) {
                v(c, m, sx0 * width, y, cz0 * width, s0);
                v(c, m, sx1 * width, y, cz1 * width, s0);
                v(c, m, sx1 * outer, y, cz1 * outer, clear);
                v(c, m, sx0 * width, y, cz0 * width, s0);
                v(c, m, sx1 * outer, y, cz1 * outer, clear);
                v(c, m, sx0 * outer, y, cz0 * outer, clear);
            }
        }
    }

    /** Цвет темы, умноженный на яркость, полностью непрозрачный. */
    private static int shade(int color, float k) {
        int r = Math.min(255, Math.round(((color >> 16) & 0xFF) * k));
        int g = Math.min(255, Math.round(((color >> 8) & 0xFF) * k));
        int bl = Math.min(255, Math.round((color & 0xFF) * k));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Сортируемый translucent-батч в 26.3 не принимает TRIANGLES (краш «Cannot sort draw with TRIANGLES»):
     *  рисуем QUADS, каждый треугольник — вырожденный квад (третья вершина дублируется). */
    private static int triVertex = 0;

    private static void v(VertexConsumer c, Matrix4f m, float x, float y, float z, int color) {
        c.addVertex(m, x, y, z).setColor(color);
        if (++triVertex == 3) {
            triVertex = 0;
            c.addVertex(m, x, y, z).setColor(color);
        }
    }

    private static void applyHead(PoseStack.Pose entry, HeadTransform head) {
        entry.translate(head.originX / 16.0F, head.originY / 16.0F, head.originZ / 16.0F);
        if (head.pitch != 0.0F || head.yaw != 0.0F || head.roll != 0.0F) {
            entry.rotate(new Quaternionf().rotationZYX(head.roll, head.yaw, head.pitch));
        }
        if (head.xScale != 1.0F || head.yScale != 1.0F || head.zScale != 1.0F) {
            entry.scale(head.xScale, head.yScale, head.zScale);
        }
    }

    private record HeadTransform(float originX, float originY, float originZ,
                                 float pitch, float yaw, float roll,
                                 float xScale, float yScale, float zScale) {
        private static HeadTransform capture(ModelPart head) {
            return new HeadTransform(head.x, head.y, head.z,
                    head.xRot, head.yRot, head.zRot,
                    head.xScale, head.yScale, head.zScale);
        }
    }

    private static RenderType coneLayer() {
        RenderType local = coneLayer;
        if (local == null) {
            synchronized (ChinaHatRenderer.class) {
                local = coneLayer;
                if (local == null) {
                    local = RenderType.create("vesence_china_hat_solid",
                            RenderSetup.builder(hatPipeline(PrimitiveTopology.QUADS, "china_hat_solid", true))
                                    .createRenderSetup());
                    coneLayer = local;
                }
            }
        }
        return local;
    }

    private static RenderType fringeLayer() {
        RenderType local = fringeLayer;
        if (local == null) {
            synchronized (ChinaHatRenderer.class) {
                local = fringeLayer;
                if (local == null) {
                    local = RenderType.create("vesence_china_hat_fringe",
                            RenderSetup.builder(hatPipeline(PrimitiveTopology.QUADS, "china_hat_fringe", false))
                                    .createRenderSetup());
                    fringeLayer = local;
                }
            }
        }
        return local;
    }

    private static RenderPipeline hatPipeline(PrimitiveTopology mode, String path, boolean depthWrite) {
        return RenderPipelines.register(
                RenderPipeline.builder(new Snippet[]{RenderPipelines.MATRICES_FOG_SNIPPET})
                        .withLocation(Identifier.fromNamespaceAndPath("vesence", path))
                        .withVertexShader("core/position_color")
                        .withFragmentShader("core/position_color")
                        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                        .withPrimitiveTopology(mode)
                        .withCull(false)
                        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, depthWrite))
                        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                        .build());
    }
}
