package xyz.mdvcraft.justice.util;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern PART = Pattern.compile("(\\d+)([smhd])", Pattern.CASE_INSENSITIVE);

    private DurationParser() {
    }

    public static OptionalLong parseMillis(String input) {
        if (input == null || input.isBlank()) return OptionalLong.empty();
        String value = input.trim().toLowerCase(Locale.ROOT);

        if (value.equals("perm") || value.equals("permanente") || value.equals("permanent")) {
            return OptionalLong.of(-1L);
        }

        Matcher matcher = PART.matcher(value);
        long total = 0L;
        int end = 0;
        boolean found = false;

        while (matcher.find()) {
            if (matcher.start() != end) return OptionalLong.empty();
            found = true;
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ex) {
                return OptionalLong.empty();
            }

            long multiplier = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                default -> 0L;
            };

            try {
                total = Math.addExact(total, Math.multiplyExact(amount, multiplier));
            } catch (ArithmeticException ex) {
                return OptionalLong.empty();
            }
            end = matcher.end();
        }

        if (!found || end != value.length()) return OptionalLong.empty();
        return OptionalLong.of(total);
    }

    public static String format(long millis) {
        if (millis < 0) return "permanente";
        long seconds = Math.max(0, millis / 1000L);
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;

        StringBuilder out = new StringBuilder();
        if (days > 0) out.append(days).append("d ");
        if (hours > 0) out.append(hours).append("h ");
        if (minutes > 0) out.append(minutes).append("m ");
        if (seconds > 0 || out.length() == 0) out.append(seconds).append("s");
        return out.toString().trim();
    }
}
