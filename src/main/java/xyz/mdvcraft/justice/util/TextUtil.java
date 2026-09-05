package xyz.mdvcraft.justice.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.util.Map;

public final class TextUtil {
    private static final LegacyComponentSerializer AMP =
            LegacyComponentSerializer.legacyAmpersand();

    private TextUtil() {
    }

    public static String color(String input) {
        if (input == null) return "";
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    public static Component component(String input) {
        return AMP.deserialize(input == null ? "" : input);
    }

    public static String replace(String input, Map<String, String> values) {
        String result = input == null ? "" : input;
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}",
                        entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return result;
    }
}
