package vesence.utils.commands;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import vesence.Vesence;
import vesence.module.impl.movement.Spider;

import java.util.List;
import java.util.Locale;

/**
 * Команда WallClimb из NurikSRC: {@code .wallclimb set/reset/info}.
 *
 * <p>В Vesence модуль называется Spider, поэтому добавлены алиасы
 * {@code .spider} и {@code .wc}; основной синтаксис оставлен совместимым
 * с оригиналом.
 */
@Environment(EnvType.CLIENT)
public final class WallClimbCommand implements Command {

   private static final WallClimbCommand INSTANCE = new WallClimbCommand();
   private static final List<String> ALIASES = List.of(".wallclimb", ".wc", ".spider");

   private WallClimbCommand() {
   }

   public static WallClimbCommand getInstance() {
      return INSTANCE;
   }

   @Override
   public String name() {
      return "wallclimb";
   }

   @Override
   public List<String> aliases() {
      return ALIASES;
   }

   @Override
   public String usage() {
      return ".wallclimb set <item> | .wallclimb reset | .wallclimb info";
   }

   @Override
   public String description() {
      return "Выбирает предмет для режима Spider / Funtime Custom Block";
   }

   @Override
   public void execute(CommandContext context, String arguments) throws CommandException {
      if (arguments == null || arguments.isBlank()) {
         context.sendInfo("Использование: " + this.usage());
         return;
      }

      String[] parts = arguments.trim().split("\\s+");
      String sub = parts[0].toLowerCase(Locale.ROOT);

      switch (sub) {
         case "set" -> this.setItem(context, parts);
         case "reset", "clear", "remove" -> this.resetItem(context);
         case "info", "get", "show" -> this.showItem(context);
         default -> throw new CommandException("Неизвестная подкоманда. Использование: " + this.usage());
      }
   }

   private void setItem(CommandContext context, String[] parts) throws CommandException {
      if (parts.length < 2) {
         throw new CommandException("Использование: .wallclimb set <item>");
      }

      String raw = String.join("_", java.util.Arrays.copyOfRange(parts, 1, parts.length));
      Item item = Spider.resolveItem(raw);
      if (item == null) {
         throw new CommandException("Неизвестный предмет: " + raw);
      }
      if (!Spider.storeItem(raw)) {
         throw new CommandException("Не удалось сохранить предмет: " + raw);
      }

      Identifier id = BuiltInRegistries.ITEM.getKey(item);
      context.sendSuccess("WallClimb item set to " + (id == null ? raw : id));
      saveConfig();
   }

   private void resetItem(CommandContext context) {
      Spider.selectedItem.set("");
      context.sendSuccess("WallClimb item is reset");
      saveConfig();
   }

   private void showItem(CommandContext context) {
      String id = Spider.selectedItemId();
      if (id == null || id.isBlank()) {
         context.sendInfo("WallClimb item not set");
      } else {
         context.sendInfo("WallClimb item: " + id);
      }
   }

   private static void saveConfig() {
      if (Vesence.get != null && Vesence.get.configManager != null) {
         Vesence.get.configManager.autoSave();
      }
   }
}
