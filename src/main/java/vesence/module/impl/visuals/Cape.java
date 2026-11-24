package vesence.module.impl.visuals;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import vesence.module.api.Category;
import vesence.module.api.IModule;
import vesence.module.api.Module;
import vesence.module.api.setting.impl.ModeSetting;
import vesence.utils.render.AnimatedLogoCapeTexture;

@Environment(EnvType.CLIENT)
@IModule(
        name = "Cape",
        description = "Кастомный плащ",
        category = Category.VISUALS
)
public class Cape extends Module {

    public static final String STYLE_STATIC = "Обычный";
    public static final String STYLE_ANIMATED = "Анимированный";

    public static final Identifier STATIC_CAPE =
            Identifier.fromNamespaceAndPath("vesence", "textures/cape/cape.png");

    public static final Identifier STATIC_ELYTRA =
            Identifier.fromNamespaceAndPath("vesence", "textures/cape/cape.png");

    public static ModeSetting style = new ModeSetting("Вид плаща", STYLE_STATIC, STYLE_STATIC, STYLE_ANIMATED);

    public Cape() {
        this.addSettings(style);
    }

    /**
     * Текстура плаща: обычная или анимированная (по настройке).
     * Пока анимация готовится в фоне, показывается обычная.
     */
    public Identifier getCapeTexture() {
        if (style.is(STYLE_ANIMATED)) {
            Identifier animated = AnimatedLogoCapeTexture.current();
            if (animated != null) return animated;
        }
        return STATIC_CAPE;
    }

    /**
     * Текстура элитры: та же картинка, что у плаща.
     */
    public Identifier getElytraTexture() {
        if (style.is(STYLE_ANIMATED)) {
            Identifier animated = AnimatedLogoCapeTexture.current();
            if (animated != null) return animated;
        }
        return STATIC_ELYTRA;
    }
}
