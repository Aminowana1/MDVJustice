package xyz.mdvcraft.justice.command;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import xyz.mdvcraft.justice.MDVJusticePlugin;
import xyz.mdvcraft.justice.prison.PrisonManager;

import java.util.Arrays;
import java.util.Map;

public final class PunishCommand implements CommandExecutor {
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
}
