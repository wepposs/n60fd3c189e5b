package vesence.utils.other;

import java.util.regex.Pattern;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents.LiteralContents;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;

/**
 * Заменяет подстроку (без учёта регистра) внутри дерева {@link Component},
 * сохраняя стили каждого сегмента. Порт из RelevantPremiumpp4
 * (ru.whylol.api.utils.replace.ReplaceUtils) для NameProtect/Streamer Mode.
 */
@Environment(EnvType.CLIENT)
public class ReplaceUtils {
    private static final java.util.Map<String, Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();


    public static Component replace(Component input, String target, String replacement) {
        if (input != null && target != null && replacement != null) {
            MutableComponent result = Component.empty().setStyle(input.getStyle());
            appendReplaced(result, input, target, replacement);
            return result;
        } else {
            return input;
        }
    }

    private static void appendReplaced(MutableComponent result, Component current, String target, String replacement) {
        ComponentContents content = current.getContents();
        Style style = current.getStyle();
        if (content instanceof LiteralContents literal) {
            Pattern pattern = PATTERNS.computeIfAbsent(target, t -> Pattern.compile(Pattern.quote(t), Pattern.CASE_INSENSITIVE));
            String replaced = pattern.matcher(literal.text()).replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
            result.append(Component.literal(replaced).setStyle(style));
        }

        for (Component sibling : current.getSiblings()) {
            appendReplaced(result, sibling, target, replacement);
        }
    }
}
