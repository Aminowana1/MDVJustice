package xyz.mdvcraft.justice.command;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import xyz.mdvcraft.justice.MDVJusticePlugin;
import xyz.mdvcraft.justice.prison.PrisonManager;

import java.util.*;

public final class ReleaseCommand implements CommandExecutor, TabCompleter {
    private final MDVJusticePlugin plugin;
    private final PrisonManager prison;

    public ReleaseCommand(MDVJusticePlugin plugin, PrisonManager prison) {
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

        if (args.length < 1) {
            plugin.send(sender, "usage-liberar", Map.of());
            return true;
        }

        OfflinePlayer target = plugin.findKnownPlayer(args[0]);
        if (target == null) {
            plugin.send(sender, "player-not-found", Map.of("player", args[0]));
            return true;
        }

        prison.requestManualRelease(sender, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length != 1) return Collections.emptyList();

        String lower = args[0].toLowerCase(Locale.ROOT);
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> prison.isOpenSentence(player.getUniqueId()))
                .map(player -> player.getName())
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted()
                .toList();
    }
}
