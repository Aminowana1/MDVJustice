package xyz.mdvcraft.justice.command;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import xyz.mdvcraft.justice.MDVJusticePlugin;
import xyz.mdvcraft.justice.chat.ChatManager;
import xyz.mdvcraft.justice.prison.MineManager;
import xyz.mdvcraft.justice.prison.PrisonManager;
import xyz.mdvcraft.justice.prison.model.Sentence;
import xyz.mdvcraft.justice.util.DurationParser;

import java.util.*;

public final class JusticeCommand implements CommandExecutor, TabCompleter {
    private final MDVJusticePlugin plugin;
    private final ChatManager chat;
    private final PrisonManager prison;
    private final MineManager mine;

    public JusticeCommand(MDVJusticePlugin plugin, ChatManager chat,
                          PrisonManager prison, MineManager mine) {
        this.plugin = plugin;
        this.chat = chat;
        this.prison = prison;
        this.mine = mine;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!plugin.isAdmin(sender)) {
            plugin.send(sender, "no-permission", Map.of());
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(sender);
            case "chat" -> handleGlobalChat(sender, args);
            case "slowchat" -> handleSlowChat(sender, args);
            case "silenciar" -> handleMute(sender, args);
            case "desilenciar" -> handleUnmute(sender, args);
            case "prision", "prisión" -> handlePrison(sender, args);
            case "mina" -> handleMine(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        mine.reload();
        mine.startScheduler();
        plugin.send(sender, "reload", Map.of());
    }

    private void handleGlobalChat(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("lento")) {
            sendHelp(sender);
            return;
        }

        if (args[2].equalsIgnoreCase("off")) {
            chat.setGlobalSlow(0L);
            plugin.broadcast("global-slow-disabled", Map.of());
            return;
        }

        OptionalLong parsed = DurationParser.parseMillis(args[2]);
        if (parsed.isEmpty() || parsed.getAsLong() < 0) {
            plugin.send(sender, "invalid-duration", Map.of());
            return;
        }

        long delay = parsed.getAsLong();
        chat.setGlobalSlow(delay);
        plugin.broadcast("global-slow-enabled", Map.of(
                "delay", DurationParser.format(delay)
        ));
    }

    private void handleSlowChat(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendHelp(sender);
            return;
        }

        OfflinePlayer target = plugin.findKnownPlayer(args[1]);
        if (target == null) {
            plugin.send(sender, "player-not-found", Map.of("player", args[1]));
            return;
        }

        if (args[2].equalsIgnoreCase("off")) {
            chat.removeManualSlow(target.getUniqueId());
            plugin.send(sender, "player-slow-removed-staff", Map.of(
                    "player", safeName(target)
            ));
            if (target.getPlayer() != null) {
                plugin.send(target.getPlayer(), "player-slow-removed-target", Map.of());
            }
            return;
        }

        if (args.length < 4) {
            sendHelp(sender);
            return;
        }

        OptionalLong delayParsed = DurationParser.parseMillis(args[2]);
        OptionalLong durationParsed = DurationParser.parseMillis(args[3]);

        if (delayParsed.isEmpty() || durationParsed.isEmpty()
                || delayParsed.getAsLong() <= 0
                || durationParsed.getAsLong() == 0) {
            plugin.send(sender, "invalid-duration", Map.of());
            return;
        }

        long delay = delayParsed.getAsLong();
        long duration = durationParsed.getAsLong();
        chat.applyManualSlow(target, delay, duration);

        Map<String, String> vars = Map.of(
                "player", safeName(target),
                "delay", DurationParser.format(delay),
                "duration", duration < 0
                        ? plugin.rawMessage("duration-permanent")
                        : DurationParser.format(duration)
        );

        plugin.send(sender, "player-slow-applied-staff", vars);
        if (target.getPlayer() != null) {
            plugin.send(target.getPlayer(), "player-slow-applied-target", vars);
        }
    }

    private void handleMute(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendHelp(sender);
            return;
        }

        OfflinePlayer target = plugin.findKnownPlayer(args[1]);
        if (target == null) {
            plugin.send(sender, "player-not-found", Map.of("player", args[1]));
            return;
        }

        OptionalLong durationParsed = DurationParser.parseMillis(args[2]);
        if (durationParsed.isEmpty() || durationParsed.getAsLong() == 0) {
            plugin.send(sender, "invalid-duration", Map.of());
            return;
        }

        long duration = durationParsed.getAsLong();
        String reason = args.length >= 4
                ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                : plugin.rawMessage("reason-none");

        String durationText = duration < 0
                ? plugin.rawMessage("duration-permanent")
                : DurationParser.format(duration);

        chat.applyMute(target, duration, reason, sender.getName());

        Map<String, String> vars = new HashMap<>();
        vars.put("player", safeName(target));
        vars.put("duration", durationText);
        vars.put("reason", reason);
        vars.put("staff", sender.getName());

        plugin.send(sender, "mute-applied-staff", vars);
        if (target.getPlayer() != null) {
            plugin.send(target.getPlayer(), "mute-applied-target", vars);
        }

        if (plugin.getConfig().getBoolean("chat.mute.broadcast", true)) {
            plugin.broadcast("mute-broadcast", vars);
        }
    }

    private void handleUnmute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }

        OfflinePlayer target = plugin.findKnownPlayer(args[1]);
        if (target == null) {
            plugin.send(sender, "player-not-found", Map.of("player", args[1]));
            return;
        }

        if (!chat.removeMute(target.getUniqueId())) {
            plugin.send(sender, "mute-not-active", Map.of());
            return;
        }

        plugin.send(sender, "mute-removed-staff", Map.of("player", safeName(target)));
        if (target.getPlayer() != null) {
            plugin.send(target.getPlayer(), "mute-removed-target", Map.of());
        }
    }

    private void handlePrison(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "setspawn" -> {
                Player player = requirePlayer(sender);
                if (player == null) return;
                saveLocation("prison.spawn", player);
                plugin.send(sender, "prison-spawn-set", Map.of());
            }
            case "setlibertad" -> {
                Player player = requirePlayer(sender);
                if (player == null) return;
                saveLocation("prison.release", player);
                plugin.send(sender, "release-spawn-set", Map.of());
            }
            case "pos1" -> setCuboidPoint(sender, "prison.region", "pos1", "prison-pos1-set");
            case "pos2" -> setCuboidPoint(sender, "prison.region", "pos2", "prison-pos2-set");
            case "info" -> {
                if (args.length < 3) {
                    sendHelp(sender);
                    return;
                }
                OfflinePlayer target = plugin.findKnownPlayer(args[2]);
                if (target == null) {
                    plugin.send(sender, "player-not-found", Map.of("player", args[2]));
                    return;
                }
                Sentence sentence = prison.sentence(target.getUniqueId());
                if (sentence == null) {
                    plugin.send(sender, "not-prisoner", Map.of("player", safeName(target)));
                    return;
                }
                plugin.send(sender, "progress-info", Map.of(
                        "player", sentence.playerName(),
                        "current", Integer.toString(sentence.currentPoints()),
                        "required", Integer.toString(sentence.requiredPoints()),
                        "remaining", Integer.toString(sentence.remaining())
                ));
            }
            default -> sendHelp(sender);
        }
    }

    private void handleMine(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "pos1" -> setMinePoint(sender, "pos1");
            case "pos2" -> setMinePoint(sender, "pos2");
            case "regenerar" -> {
                MineManager.RegenerationResult result = mine.regenerate();

                switch (result) {
                    case STARTED ->
                            plugin.send(sender, "mine-regeneration-started", Map.of());
                    case BUSY ->
                            plugin.send(sender, "mine-regeneration-busy", Map.of());
                    case NOT_CONFIGURED ->
                            plugin.send(sender, "mine-not-configured", Map.of());
                    case NO_VALID_BLOCKS ->
                            plugin.send(sender, "mine-no-valid-blocks", Map.of());
                    case WORLD_UNAVAILABLE ->
                            plugin.send(sender, "mine-world-unavailable", Map.of());
                    case TOO_LARGE -> {
                        long volume = mine.region() == null ? 0L : mine.region().volume();
                        plugin.send(sender, "mine-volume-too-large", Map.of(
                                "volume", Long.toString(volume),
                                "max", Long.toString(mine.maxVolume())
                        ));
                    }
                }
            }
            default -> sendHelp(sender);
        }
    }

    private Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "players-only", Map.of());
            return null;
        }
        return player;
    }

    private void saveLocation(String path, Player player) {
        plugin.getConfig().set(path + ".world", player.getWorld().getName());
        plugin.getConfig().set(path + ".x", player.getLocation().getX());
        plugin.getConfig().set(path + ".y", player.getLocation().getY());
        plugin.getConfig().set(path + ".z", player.getLocation().getZ());
        plugin.getConfig().set(path + ".yaw", player.getLocation().getYaw());
        plugin.getConfig().set(path + ".pitch", player.getLocation().getPitch());
        plugin.saveConfig();
    }

    private void setCuboidPoint(CommandSender sender, String path, String point, String message) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        plugin.getConfig().set(path + ".world", player.getWorld().getName());
        plugin.getConfig().set(path + "." + point + ".x", player.getLocation().getBlockX());
        plugin.getConfig().set(path + "." + point + ".y", player.getLocation().getBlockY());
        plugin.getConfig().set(path + "." + point + ".z", player.getLocation().getBlockZ());
        plugin.saveConfig();
        plugin.send(sender, message, Map.of());
    }

    private void setMinePoint(CommandSender sender, String point) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        String path = "mine.region";
        String currentWorld = player.getWorld().getName();
        String storedWorld = plugin.getConfig().getString(path + ".world", "");

        boolean pos1Set = plugin.getConfig().getBoolean(path + ".pos1-set", false);
        boolean pos2Set = plugin.getConfig().getBoolean(path + ".pos2-set", false);

        // Si una esquina fue tomada en otro mundo, se invalida por seguridad.
        if (storedWorld != null && !storedWorld.isBlank()
                && !storedWorld.equalsIgnoreCase(currentWorld)) {
            pos1Set = false;
            pos2Set = false;
            plugin.getConfig().set(path + ".pos1-set", false);
            plugin.getConfig().set(path + ".pos2-set", false);
            plugin.getConfig().set(path + ".configured", false);
            plugin.send(sender, "mine-region-world-reset", Map.of());
        }

        plugin.getConfig().set(path + ".world", currentWorld);
        plugin.getConfig().set(path + "." + point + ".x", player.getLocation().getBlockX());
        plugin.getConfig().set(path + "." + point + ".y", player.getLocation().getBlockY());
        plugin.getConfig().set(path + "." + point + ".z", player.getLocation().getBlockZ());
        plugin.getConfig().set(path + "." + point + "-set", true);

        if (point.equals("pos1")) pos1Set = true;
        if (point.equals("pos2")) pos2Set = true;

        boolean configured = pos1Set && pos2Set;
        plugin.getConfig().set(path + ".configured", configured);
        plugin.saveConfig();

        if (!configured) {
            plugin.send(sender,
                    point.equals("pos1") ? "mine-pos1-set" : "mine-pos2-set",
                    Map.of());
            return;
        }

        mine.reload();
        long volume = mine.region() == null ? 0L : mine.region().volume();

        if (volume <= 0L || volume > mine.maxVolume()) {
            plugin.getConfig().set(path + ".configured", false);
            plugin.saveConfig();

            plugin.send(sender, "mine-volume-too-large", Map.of(
                    "volume", Long.toString(volume),
                    "max", Long.toString(mine.maxVolume())
            ));
            return;
        }

        plugin.send(sender, "mine-region-ready", Map.of(
                "volume", Long.toString(volume)
        ));
    }

    private void sendHelp(CommandSender sender) {
        for (String line : plugin.getConfig().getStringList("messages.help")) {
            sender.sendMessage(xyz.mdvcraft.justice.util.TextUtil.color(line));
        }
    }

    private String safeName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            return partial(args[0], List.of(
                    "reload", "chat", "slowchat", "silenciar",
                    "desilenciar", "prision", "mina"));
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("chat")) {
            return partial(args[1], List.of("lento"));
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("prision")) {
            return partial(args[1], List.of(
                    "setspawn", "setlibertad", "pos1", "pos2", "info"));
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("mina")) {
            return partial(args[1], List.of("pos1", "pos2", "regenerar"));
        }

        if (args.length == 2 && List.of("slowchat", "silenciar", "desilenciar")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            return partial(args[1], Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName).toList());
        }

        // /justice chat lento <delay|off>
        if (args.length == 3
                && args[0].equalsIgnoreCase("chat")
                && args[1].equalsIgnoreCase("lento")) {
            return partial(args[2], chatDelaySuggestions());
        }

        // /justice slowchat <jugador> <delay|off>
        if (args.length == 3 && args[0].equalsIgnoreCase("slowchat")) {
            return partial(args[2], chatDelaySuggestions());
        }

        // /justice slowchat <jugador> <delay> <duracion>
        if (args.length == 4
                && args[0].equalsIgnoreCase("slowchat")
                && !args[2].equalsIgnoreCase("off")) {
            return partial(args[3], sanctionDurationSuggestions());
        }

        // /justice silenciar <jugador> <duracion>
        if (args.length == 3 && args[0].equalsIgnoreCase("silenciar")) {
            return partial(args[2], sanctionDurationSuggestions());
        }

        if (args.length == 3
                && args[0].equalsIgnoreCase("prision")
                && args[1].equalsIgnoreCase("info")) {
            return partial(args[2], Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName).toList());
        }

        return Collections.emptyList();
    }

    private List<String> chatDelaySuggestions() {
        List<String> configured = plugin.getConfig()
                .getStringList("commands.tab-suggestions.chat-delays");
        return configured.isEmpty()
                ? List.of("off", "1s", "3s", "5s", "10s", "30s", "1m")
                : configured;
    }

    private List<String> sanctionDurationSuggestions() {
        List<String> configured = plugin.getConfig()
                .getStringList("commands.tab-suggestions.sanction-durations");
        return configured.isEmpty()
                ? List.of("10m", "30m", "1h", "6h", "12h", "1d", "3d", "7d", "permanente")
                : configured;
    }

    private List<String> partial(String token, Collection<String> values) {
        String lower = token.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted()
                .toList();
    }
}
