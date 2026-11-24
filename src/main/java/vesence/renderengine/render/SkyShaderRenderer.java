package vesence.renderengine.render;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.minecraft.client.Camera;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SkyShaderRenderer — полный порт Relevant ShaderSky на core-пайплайн 1.21.11.
 *
 * <p>Точный перенос логики Relevant: небо рисуется НА СТАДИИ НЕБА (из миксина
 * {@code SkyRenderingMixin} на {@code renderTopSky} с отменой ванильного неба).
 * Т.к. это происходит ДО отрисовки геометрии мира, земля/деревья потом
 * перекрывают небо естественным образом — не нужна ни маска глубины, ни
 * композит, и НЕТ бага с чернением при взгляде вверх.
 *
 * <p>Рисуем полноэкранный квад выбранного шейдера прямо в цветовой буфер кадра
 * без теста/записи глубины.
 */
@Environment(EnvType.CLIENT)
public final class SkyShaderRenderer {

    public static final String[] SHADER_NAMES = {
            "sky_summer", "sky_clouds", "sky_sunset", "sky_dawn", "sky_neon",
            "sky_inferno", "sky_psychedelic", "sky_moon", "sky_eclipse", "sky_crystal",
            "sky_volcano", "sky_glacier"
    };

    public static final String[] MODE_NAMES = {
            "Лето", "Облака", "Закат", "Рассвет", "Неон",
            "Инферно", "Психоделика", "Луна", "Затмение", "Кристалл",
            "Вулкан", "Ледник"
    };

    private static final Identifier SKY_VSH = Identifier.fromNamespaceAndPath("vesence", "core/sky/sky_fullscreen");

    // Небо рисуется поверх содержимого буфера с учётом собственной альфы, чтобы
    // туман (см. fsh) мог плавно смешиваться. Обычно альфа=1 → полная замена.
    private static final BlendFunction REPLACE_BLEND =
            new BlendFunction(BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE, BlendFactor.ZERO);

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f(0, 0, 0);
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final int SKY_UBO_SIZE = 48; // 3 x vec4

    private final Map<String, RenderPipeline> skyPipelines = new HashMap<>();
    private GpuBuffer skyUbo;
    private GpuBuffer dummyVertexBuffer;
    private ByteBuffer skyData;
    private boolean initialized = false;

    // Облака считаются отдельным проходом в HDR-буфер уменьшенного размера,
    // небо, солнце и атмосфера — в полном разрешении поверх него.
    private GpuBuffer cloudUbo;
    private com.mojang.renderpearl.api.textures.GpuTexture lowTex;
    private com.mojang.renderpearl.api.textures.GpuTextureView lowView;
    private int lowW, lowH;
    // Поколение пайплайнов: при перезагрузке ресурсов (F3+T) инкрементируется,
    // новые локации заставляют GPU скомпилировать шейдеры из свежих исходников.
    private int pipelineGeneration = 0;
    private final long startTime = System.currentTimeMillis();

    private static SkyShaderRenderer instance;

    public static SkyShaderRenderer getInstance() {
        if (instance == null) instance = new SkyShaderRenderer();
        return instance;
    }

    private void ensureInitialized() {
        if (initialized) return;
        this.skyData = MemoryUtil.memAlloc(SKY_UBO_SIZE);
        ByteBuffer dummy = MemoryUtil.memAlloc(4);
        dummy.putInt(0);
        dummy.flip();
        this.dummyVertexBuffer = RenderSystem.getDevice().createBuffer(
                () -> "vesence:sky_dummy_vertex", GpuBuffer.USAGE_VERTEX, dummy);
        MemoryUtil.memFree(dummy);
        this.skyUbo = RenderSystem.getDevice().createBuffer(
                () -> "vesence:sky_ubo", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, SKY_UBO_SIZE);
        this.cloudUbo = RenderSystem.getDevice().createBuffer(
                () -> "vesence:sky_cloud_ubo", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, SKY_UBO_SIZE);
        initialized = true;
    }

    private RenderPipeline getSkyPipeline(String shaderName) {
        return getSkyPipeline(shaderName, false);
    }

    /**
     * @param hdr пайплайн для прохода облаков: цель в формате RGBA16F. Формат
     *            цели обязан совпадать с форматом пайплайна, иначе проход падает.
     */
    private RenderPipeline getSkyPipeline(String shaderName, boolean hdr) {
        String key = hdr ? shaderName + "#hdr" : shaderName;
        if (skyPipelines.containsKey(key)) return skyPipelines.get(key);
        try {
            Identifier fsh = Identifier.fromNamespaceAndPath("vesence", "core/sky/" + shaderName);
            RenderPipeline p = RenderPipelines.register(
                    RenderPipeline.builder()
                            .withLocation(Identifier.fromNamespaceAndPath("vesence", "pipeline/" + shaderName + (hdr ? "_hdr" : "") + "_g" + pipelineGeneration))
                            .withVertexShader(SKY_VSH)
                            .withFragmentShader(fsh)
                            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                            .withBindGroupLayout(BindGroupLayout.builder()
                                    .withUniform("SkyData", UniformType.UNIFORM_BUFFER)
                                    .withUniform("CloudNoise", UniformType.COMBINED_IMAGE_SAMPLER)
                                    .withUniform("CloudBuffer", UniformType.COMBINED_IMAGE_SAMPLER)
                                    .build())
                            .withDepthStencilState(Optional.empty())
                            .withColorTargetState(hdr
                                    ? new ColorTargetState(Optional.empty(), com.mojang.renderpearl.api.GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL)
                                    : new ColorTargetState(REPLACE_BLEND))
                            .withCull(false)
                            .build()
            );
            skyPipelines.put(key, p);
            return p;
        } catch (Exception e) {
            System.err.println("[weppos] Sky pipeline register failed (" + shaderName + "): " + e.getMessage());
            skyPipelines.put(key, null);
            return null;
        }
    }

    private void ensureLowTarget(int w, int h) {
        if (lowTex != null && lowW == w && lowH == h) return;
        closeLowTarget();
        lowW = w;
        lowH = h;
        lowTex = RenderSystem.getDevice().createTexture(() -> "vesence:sky_lowres",
                com.mojang.renderpearl.api.textures.GpuTexture.USAGE_RENDER_ATTACHMENT
                        | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_TEXTURE_BINDING
                        | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_DST,
                com.mojang.renderpearl.api.GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
        lowView = RenderSystem.getDevice().createTextureView(lowTex);
    }

    private void closeLowTarget() {
        if (lowView != null) { lowView.close(); lowView = null; }
        if (lowTex != null) { lowTex.close(); lowTex = null; }
    }

    /** Доля разрешения кадра, в которой считается небо (настройка Ambience). */
    private static float resolutionScale() {
        try {
            String m = vesence.module.impl.visuals.Ambience.skyResolution.currentMode;
            if ("100%".equals(m)) return 1.0f;
            if ("75%".equals(m)) return 0.75f;
        } catch (Throwable ignored) {
        }
        return 0.5f;
    }

    /**
     * Вызывается при перезагрузке ресурсов (F3+T): сбрасывает кэш пайплайнов,
     * чтобы шейдеры неба скомпилировались заново из свежих файлов.
     */
    public void reloadPipelines() {
        pipelineGeneration++;
        skyPipelines.clear();
    }

    public String modeToShaderName(String mode) {
        for (int i = 0; i < MODE_NAMES.length; i++) {
            if (MODE_NAMES[i].equals(mode)) return SHADER_NAMES[i];
        }
        return SHADER_NAMES[0];
    }

    /** Диагностика для скай-теста: скомпилировался ли пайплайн режима. */
    public boolean debugPipelineReady(String shaderName) {
        ensureInitialized();
        return getSkyPipeline(shaderName) != null;
    }

    /**
     * Рисует кастомное небо прямо в цветовой буфер кадра (стадия неба).
     *
     * @param fogEnabled слить низ неба с цветом тумана
     * @param fogColorRgb 0xRRGGBB
     * @param fogBlend    сила слияния 0..1
     */
    public boolean render(RenderTarget mainFb, String mode, Camera camera, float speed, float intensity,
                          boolean fogEnabled, int fogColorRgb, float fogBlend) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || camera == null) return false;
        // Use SkyRenderer's scene target, not the final post-processing/output target.
        if (mainFb == null || mainFb.getColorTexture() == null) return false;

        int w = mainFb.width;
        int h = mainFb.height;
        if (w <= 0 || h <= 0) return false;

        ensureInitialized();
        RenderPipeline skyPipeline = getSkyPipeline(modeToShaderName(mode));
        if (skyPipeline == null) return false;

        float time = (float) (System.currentTimeMillis() - startTime) / 1000.0F * speed;
        float camYaw = camera.yRot();
        float camPitch = camera.xRot();
        float fov = mc.options.fov().get().floatValue();

        float fr = ((fogColorRgb >> 16) & 0xFF) / 255.0f;
        float fg = ((fogColorRgb >> 8) & 0xFF) / 255.0f;
        float fb = (fogColorRgb & 0xFF) / 255.0f;

        float scale = resolutionScale();
        boolean split = scale < 0.999f;
        int rw = Math.max(1, Math.round(w * scale));
        int rh = Math.max(1, Math.round(h * scale));
        RenderPipeline cloudPipeline = split ? getSkyPipeline(modeToShaderName(mode), true) : null;
        if (cloudPipeline == null) split = false;
        if (split) ensureLowTarget(rw, rh);

        // Текстуру шума создаём до открытия RenderPass: загрузка внутри прохода запрещена.
        var cloudNoise = CloudNoiseTexture.view();
        var linearRepeat = RenderSystem.getSamplerCache().getRepeat(com.mojang.renderpearl.api.textures.FilterMode.LINEAR);
        var linearClamp = RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.LINEAR);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        writeSkyUbo(encoder, skyUbo, w, h, time, intensity, camYaw, camPitch, fov, split ? 2f : 0f, fr, fg, fb, fogEnabled ? fogBlend : 0f);
        if (split) {
            writeSkyUbo(encoder, cloudUbo, rw, rh, time, intensity, camYaw, camPitch, fov, 1f, fr, fg, fb, 0f);
        }

        GpuBufferSlice dyn = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        if (split) {
            // Проход 1: только облака, в HDR-буфер уменьшенного размера.
            try (RenderPass pass = encoder.createRenderPass(() -> "vesence:sky_clouds", lowView, Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(cloudPipeline));
                pass.setVertexBuffer(0, dummyVertexBuffer.slice());
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", dyn);
                pass.setUniform("SkyData", cloudUbo);
                pass.setUniform("CloudNoise", cloudNoise, linearRepeat);
                pass.setUniform("CloudBuffer", cloudNoise, linearRepeat); // не читается в этом проходе
                pass.draw(6, 1, 0, 0);
            }
        }

        // Проход 2 (или единственный): небо в полном разрешении.
        try (RenderPass pass = encoder.createRenderPass(() -> "vesence:sky_render",
                mainFb.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(skyPipeline));
            pass.setVertexBuffer(0, dummyVertexBuffer.slice());
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dyn);
            pass.setUniform("SkyData", skyUbo);
            pass.setUniform("CloudNoise", cloudNoise, linearRepeat);
            pass.setUniform("CloudBuffer", split ? lowView : cloudNoise, split ? linearClamp : linearRepeat);
            // RenderPearl: vertexCount, instanceCount, firstVertex, firstInstance.
            pass.draw(6, 1, 0, 0);
        }
        return true;
    }

    private void writeSkyUbo(CommandEncoder encoder, GpuBuffer ubo, int w, int h, float time, float intensity,
                             float yaw, float pitch, float fov, float passFlag,
                             float fr, float fg, float fb, float fogBlend) {
        skyData.clear();
        skyData.putFloat(w).putFloat(h).putFloat(time).putFloat(intensity);
        skyData.putFloat(yaw).putFloat(pitch).putFloat(fov).putFloat(passFlag);
        skyData.putFloat(fr).putFloat(fg).putFloat(fb).putFloat(fogBlend);
        skyData.flip();
        encoder.writeToBuffer(ubo.slice(), skyData);
    }

    public void close() {
        if (skyUbo != null) { skyUbo.close(); skyUbo = null; }
        if (dummyVertexBuffer != null) { dummyVertexBuffer.close(); dummyVertexBuffer = null; }
        if (skyData != null) { MemoryUtil.memFree(skyData); skyData = null; }
        if (cloudUbo != null) { cloudUbo.close(); cloudUbo = null; }
        closeLowTarget();
        skyPipelines.clear();
        CloudNoiseTexture.close();
        initialized = false;
    }
}
