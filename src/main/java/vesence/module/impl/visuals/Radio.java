package vesence.module.impl.visuals;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import vesence.Vesence;
import vesence.event.EventInit;
import vesence.event.impl.EventUpdate;
import vesence.module.api.Category;
import vesence.module.api.Module;
import vesence.module.api.setting.impl.ModeSetting;
import vesence.module.api.setting.impl.SliderSetting;
import vesence.utils.media.RadioPlayer;

/**
 * Radio — интернет-радио в стиле автомобильного приёмника. Играет выбранную
 * станцию ({@link RadioPlayer}) и показывает HUD-элемент «Radio» с эквалайзером.
 *
 * <p>Категория {@link Category#DISPLAY}: включение модуля включает одноимённый
 * HUD-элемент и запускает поток; выключение — глушит звук и прячет элемент.
 * Смена станции/громкости подхватывается на лету в {@link #onUpdate}.
 */
@Environment(EnvType.CLIENT)
public class Radio extends Module {

    private static final String ELEMENT = "Radio";

    private final ModeSetting station = new ModeSetting(
            "Станция", RadioPlayer.STATIONS[0].name(), RadioPlayer.stationNames());
    private final SliderSetting volume = new SliderSetting("Громкость", 60, 0, 100, 1);

    public Radio() {
        super("Radio", Category.DISPLAY);
        addSetting(station);
        addSetting(volume);

        // Играем только в мире (сервер/одиночка): в меню — тишина. Тик клиента
        // идёт всегда, поэтому ловит и вход в мир, и выход в меню.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RadioPlayer radio = RadioPlayer.get();
            boolean inWorld = client.level != null && client.player != null;
            if (this.enable && inWorld) {
                radio.setVolume(volume.get().floatValue() / 100f);
                radio.play(RadioPlayer.byName(station.get()));
            } else if (radio.current() != null) {
                radio.stop();
            }
        });

        Hud hud = hud();
        if (hud != null) {
            HudElement element = hud.getElement(ELEMENT);
            if (element != null) addSetting(element.scaleSetting);
        }
    }

    private Hud hud() {
        if (Vesence.get == null || Vesence.get.manager == null) return null;
        return Vesence.get.manager.get(Hud.class);
    }

    @Override
    public void onEnable() {
        super.onEnable();
        Hud hud = hud();
        if (hud != null) {
            hud.setElementEnabled(ELEMENT, true);
            if (!hud.enable) hud.toggle();
        }
        // Сам звук запускает тик клиента — и только когда игрок в мире.
    }

    @Override
    public void onDisable() {
        super.onDisable();
        RadioPlayer.get().stop();
        Hud hud = hud();
        if (hud != null) hud.setElementEnabled(ELEMENT, false);
    }

}
