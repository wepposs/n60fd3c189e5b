package vesence.module.impl.visuals;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.player.Player;
import vesence.module.api.Category;
import vesence.module.api.IModule;
import vesence.module.api.Module;
import vesence.module.api.setting.Setting;
import vesence.module.api.setting.impl.BooleanSetting;
import vesence.module.impl.visuals.chinahat.ChinaHatRenderer;
import vesence.utils.render.ColorUtil;

/** Китайская шляпа над головой игрока (рисуется в пространстве модели головы). */
@Environment(EnvType.CLIENT)
@IModule(name = "ChinaHat", description = "Китайская шляпа над головой", category = Category.VISUALS)
public class ChinaHat extends Module {

    public final BooleanSetting friends = new BooleanSetting("Отображать на друзьях", true);

    private final ChinaHatRenderer renderer = new ChinaHatRenderer(this);

    public ChinaHat() {
        this.addSettings(new Setting[]{this.friends});
    }

    /** Вызывается из LivingEntityRendererMixin. */
    public void renderOnHead(Player player, PoseStack matrices, Model<?> model, SubmitNodeCollector queue) {
        if (!this.enable) return;
        if (model instanceof HumanoidModel<?> biped) {
            this.renderer.renderOnHead(player, matrices, biped, queue);
        }
    }

    public boolean showChinaHatOnFriends() {
        return this.friends.get();
    }

    public int resolveThemeColor() {
        return ColorUtil.theme(255);
    }
}
