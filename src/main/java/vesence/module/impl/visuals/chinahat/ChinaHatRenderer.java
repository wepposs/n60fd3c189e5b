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
import vesence.utils.render.ColorUtil;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;

/** China hat - 1:1 port of Minced ChinaHat (EventLayerHead, model space). */
public final class ChinaHatRenderer {

    private static final float OFFSET_BARE = 0.42F;
    private static final float OFFSET_HELMET = 0.50F;
    private static final float ROT_Z_DEGREES = 180.0F;
    private static final float ROT_Y_DEGREES = 90.0F;
    private static final float RIM_Y = -0.01F;
    private static final float RIM_THICKNESS = 0.01F;
    private static final float RIM_FLARE = 0.01F;
    private static final float SEG_STEP = 0.034906585F;
    private static final float CONE_APEX_HEIGHT = 0.3F;
    private static final float FEATHER = 0.035F;

    private static volatile RenderType coneLayer;
    private static volatile RenderType rimLayer;

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
            drawCone(entry.pose(), consumer, theme, width);
        });

        queue.submitCustomGeometry(matrices, rimLayer(), (entry, consumer) -> {
            applyHead(entry, head);
            entry.translate(0.0F, -offset, 0.0F);
            entry.rotate(Axis.ZN.rotationDegrees(ROT_Z_DEGREES));
            entry.rotate(Axis.YP.rotationDegrees(ROT_Y_DEGREES));
            drawRim(entry.pose(), consumer, theme, width);
        });
    }

    /** Сплошной матовый конус: без прозрачности и градиента, лёгкая заливка светом сбоку. */
    private static void drawCone(Matrix4f matrix, VertexConsumer consumer, int theme, float width) {
        int apex = shade(theme, 1.08F);
        for (int i = 0; i <= 180; i++) {
            float angle = i * SEG_STEP;
            float light = 0.86F + 0.14F * Mth.cos(angle - 0.8F);
            float x = (float) (Mth.sin(angle) * width);
            float z = (float) (Mth.cos(angle) * width);
            consumer.addVertex(matrix, x, 0.0F, z).setColor(shade(theme, light));
            consumer.addVertex(matrix, 0.0F, CONE_APEX_HEIGHT, 0.0F).setColor(apex);
        }
    }

    /** Цвет темы, умноженный на яркость, полностью непрозрачный. */
    private static int shade(int color, float k) {
        int r = Math.min(255, Math.round(((color >> 16) & 0xFF) * k));
        int g = Math.min(255, Math.round(((color >> 8) & 0xFF) * k));
        int bl = Math.min(255, Math.round((color & 0xFF) * k));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static void drawRim(Matrix4f matrix, VertexConsumer consumer, int theme, float width) {
        float yLow = RIM_Y;
        float yHigh = RIM_Y + RIM_THICKNESS;
        float outer = width + RIM_FLARE;
        float inner = width - RIM_FLARE;

        float prevOuterX = (float) (Mth.sin(0.0F) * outer);
        float prevOuterZ = (float) (Mth.cos(0.0F) * outer);
        float prevInnerX = (float) (Mth.sin(0.0F) * inner);
        float prevInnerZ = (float) (Mth.cos(0.0F) * inner);

        for (int i = 1; i <= 181; i++) {
            float angle = i * SEG_STEP;
            int color = shade(theme, 0.8F); // ободок чуть темнее конуса, без прозрачности
            float outerX = (float) (Mth.sin(angle) * outer);
            float outerZ = (float) (Mth.cos(angle) * outer);
            float innerX = (float) (Mth.sin(angle) * inner);
            float innerZ = (float) (Mth.cos(angle) * inner);

            consumer.addVertex(matrix, prevOuterX, yLow, prevOuterZ).setColor(color);
            consumer.addVertex(matrix, prevOuterX, yHigh, prevOuterZ).setColor(color);
            consumer.addVertex(matrix, outerX, yHigh, outerZ).setColor(color);
            consumer.addVertex(matrix, outerX, yLow, outerZ).setColor(color);

            consumer.addVertex(matrix, prevInnerX, yHigh, prevInnerZ).setColor(color);
            consumer.addVertex(matrix, innerX, yHigh, innerZ).setColor(color);
            consumer.addVertex(matrix, outerX, yHigh, outerZ).setColor(color);
            consumer.addVertex(matrix, prevOuterX, yHigh, prevOuterZ).setColor(color);

            // Мягкая кайма наружу (альфа -> 0): сглаживает зубчатый край без MSAA.
            // Запись глубины у шляпы выключена, иначе прозрачная кайма прорезает дыры.
            int clear = ColorUtil.replAlpha(color, 0);
            float fo = outer + FEATHER;
            float pfx = (float) (Mth.sin(angle - SEG_STEP) * fo), pfz = (float) (Mth.cos(angle - SEG_STEP) * fo);
            float fx = (float) (Mth.sin(angle) * fo), fz = (float) (Mth.cos(angle) * fo);
            consumer.addVertex(matrix, prevOuterX, yHigh, prevOuterZ).setColor(color);
            consumer.addVertex(matrix, outerX, yHigh, outerZ).setColor(color);
            consumer.addVertex(matrix, fx, yHigh, fz).setColor(clear);
            consumer.addVertex(matrix, pfx, yHigh, pfz).setColor(clear);

            prevOuterX = outerX;
            prevOuterZ = outerZ;
            prevInnerX = innerX;
            prevInnerZ = innerZ;
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
                    local = RenderType.create("vesence_china_hat_cone",
                            RenderSetup.builder(hatPipeline(PrimitiveTopology.TRIANGLE_STRIP, "china_hat_cone"))
                                    .createRenderSetup());
                    coneLayer = local;
                }
            }
        }
        return local;
    }

    private static RenderType rimLayer() {
        RenderType local = rimLayer;
        if (local == null) {
            synchronized (ChinaHatRenderer.class) {
                local = rimLayer;
                if (local == null) {
                    local = RenderType.create("vesence_china_hat_rim",
                            RenderSetup.builder(hatPipeline(PrimitiveTopology.QUADS, "china_hat_rim"))
                                    .createRenderSetup());
                    rimLayer = local;
                }
            }
        }
        return local;
    }

    private static RenderPipeline hatPipeline(PrimitiveTopology mode, String path) {
        return RenderPipelines.register(
                RenderPipeline.builder(new Snippet[]{RenderPipelines.MATRICES_FOG_SNIPPET})
                        .withLocation(Identifier.fromNamespaceAndPath("vesence", path))
                        .withVertexShader("core/position_color")
                        .withFragmentShader("core/position_color")
                        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)                        .withPrimitiveTopology(mode)
                        .withCull(false)
                        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                        .build());
    }
}
