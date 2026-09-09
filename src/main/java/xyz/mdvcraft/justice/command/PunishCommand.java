package xyz.mdvcraft.justice.command;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import xyz.mdvcraft.justice.MDVJusticePlugin;
import xyz.mdvcraft.justice.prison.PrisonManager;

import java.util.*;

public final class PunishCommand implements CommandExecutor, TabCompleter {
    private final MDVJusticePlugin plugin;
    private final PrisonManager prison;

    public PunishCommand(MDVJusticePlugin plugin, PrisonManager prison) {
        this.plugin = plugin;
        this.prison = prison;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!plugin.isAdmin(sender)) {
            plugin.send(sender, "no-permission", Map.of());
            return true;
        }

        if (args.length < 2) {
            plugin.send(sender, "usage-castigar", Map.of());
            return true;
        }

        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null) {
            plugin.send(sender, "player-not-found", Map.of("player", args[0]));
            return true;
        }

        int points;
        try {
            points = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            plugin.send(sender, "invalid-number", Map.of());
            return true;
        }

        if (points <= 0) {
            plugin.send(sender, "invalid-number", Map.of());
            return true;
        }

        String reason = args.length >= 3
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : plugin.rawMessage("reason-none");

        prison.punish(sender, target, points, reason);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            return partial(args[0], plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName).toList());
        }

        if (args.length == 2) {
            List<String> configured = plugin.getConfig()
                    .getStringList("commands.tab-suggestions.prison-points");
            if (configured.isEmpty()) {
                configured = List.of("100", "250", "500", "1000", "2500", "5000");
            }
            return partial(args[1], configured);
        }

        return Collections.emptyList();
    }

    private List<String> partial(String token, Collection<String> values) {
        String lower = token.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted()
                .toList();
    }
}
