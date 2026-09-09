package xyz.mdvcraft.justice.prison;

import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import xyz.mdvcraft.justice.MDVJusticePlugin;
import xyz.mdvcraft.justice.database.JusticeRepository;
import xyz.mdvcraft.justice.prison.model.Cuboid;
import xyz.mdvcraft.justice.prison.model.PlayerSnapshot;
import xyz.mdvcraft.justice.prison.model.Sentence;
import xyz.mdvcraft.justice.util.TextUtil;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PrisonManager {
    private final MDVJusticePlugin plugin;
    private final JusticeRepository repository;
    private final PrisonKitManager kitManager;

    private final Map<UUID, Sentence> sentences = new ConcurrentHashMap<>();
    private final Set<UUID> enforced = ConcurrentHashMap.newKeySet();
    private final Set<UUID> internalTeleports = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> actionBarTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> authLockTasks = new ConcurrentHashMap<>();
    private BukkitTask kitGuardTask;

    public PrisonManager(MDVJusticePlugin plugin, JusticeRepository repository) throws SQLException {
        this.plugin = plugin;
        this.repository = repository;
        this.kitManager = new PrisonKitManager(plugin);
        this.sentences.putAll(repository.loadOpenSentences());
        startKitGuard();
    }

    public PrisonKitManager kitManager() {
        return kitManager;
    }

    public boolean isActive(UUID uuid) {
        Sentence sentence = sentences.get(uuid);
        return sentence != null && "ACTIVE".equals(sentence.status());
    }

    public boolean isOpenSentence(UUID uuid) {
        return sentences.containsKey(uuid);
    }

    public boolean isEnforced(UUID uuid) {
        return enforced.contains(uuid);
    }

    public Sentence sentence(UUID uuid) {
        return sentences.get(uuid);
    }

    public long prisonChatDelayMillis() {
        return plugin.durationOrZero(plugin.getConfig().getString("prison.chat-delay", "40s"));
    }

    public Cuboid prisonRegion() {
        return Cuboid.from(plugin.getConfig().getConfigurationSection("prison.region"));
    }

    public boolean isInsidePrison(Location location) {
        Cuboid cuboid = prisonRegion();
        return cuboid != null && cuboid.contains(location);
    }

    public boolean prisonConfigured() {
        return prisonSpawn() != null && prisonRegion() != null;
    }

    public Location prisonSpawn() {
        return readLocation("prison.spawn");
    }

    public Location releaseLocation() {
        Location configured = readLocation("prison.release");
        if (configured != null) return configured;

        String fallback = plugin.getConfig().getString("prison.release.fallback-world", "world5");
        World world = Bukkit.getWorld(fallback);
        return world == null ? null : world.getSpawnLocation();
    }

    private Location readLocation(String path) {
        String worldName = plugin.getConfig().getString(path + ".world", "");
        if (worldName == null || worldName.isBlank()) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;

        return new Location(
                world,
                plugin.getConfig().getDouble(path + ".x"),
                plugin.getConfig().getDouble(path + ".y"),
                plugin.getConfig().getDouble(path + ".z"),
                (float) plugin.getConfig().getDouble(path + ".yaw"),
                (float) plugin.getConfig().getDouble(path + ".pitch")
        );
    }

    public boolean punish(CommandSender staff, Player target, int requiredPoints, String reason) {
        if (!plugin.getConfig().getBoolean("prison.enabled", true)) {
            plugin.send(staff, "feature-disabled", Map.of());
            return false;
        }
        if (!prisonConfigured()) {
            plugin.send(staff, "prison-not-configured", Map.of());
            return false;
        }
        if (sentences.containsKey(target.getUniqueId())) {
            plugin.send(staff, "already-prisoner", Map.of("player", target.getName()));
            return false;
        }

        PlayerSnapshot snapshot = PlayerSnapshot.capture(target);
        Sentence sentence = new Sentence(
                target.getUniqueId(),
                target.getName(),
                "ACTIVE",
                requiredPoints,
                0,
                reason,
                staff.getName(),
                System.currentTimeMillis(),
                snapshot
        );

        try {
            repository.createSentence(sentence);
        } catch (SQLException ex) {
            plugin.databaseError("creando condena", ex);
            return false;
        }

        sentences.put(target.getUniqueId(), sentence);
        enforced.add(target.getUniqueId());

        applyPrisonState(target, true);
        runConsoleCommands("prison.integrations.on-punish-console-commands", target);
        showScreenTitle(target, "prison.screen-titles.punish");
        updateBossBar(target);

        plugin.send(staff, "punished-staff", Map.of(
                "player", target.getName(),
                "required", Integer.toString(requiredPoints),
                "reason", reason
        ));
        plugin.send(target, "punished-target", Map.of(
                "player", target.getName(),
                "required", Integer.toString(requiredPoints),
                "reason", reason,
                "staff", staff.getName()
        ));
        plugin.broadcast("punished-broadcast", Map.of(
                "player", target.getName(),
                "required", Integer.toString(requiredPoints),
                "reason", reason,
                "staff", staff.getName()
        ));

        return true;
    }

    public void addProgress(Player player, int amount) {
        Sentence sentence = sentences.get(player.getUniqueId());
        if (sentence == null || !"ACTIVE".equals(sentence.status()) || amount <= 0) return;

        sentence.addPoints(amount);

        try {
            repository.updateSentenceProgress(sentence.uuid(), sentence.currentPoints());
        } catch (SQLException ex) {
            plugin.databaseError("guardando progreso de condena", ex);
        }

        updateBossBar(player);
        pulseActionBar(player, amount);

        if (sentence.completed()) {
            releaseOnline(player, sentence, null, false);
        }
    }

    public boolean requestManualRelease(CommandSender staff, OfflinePlayer target) {
        Sentence sentence = sentences.get(target.getUniqueId());
        if (sentence == null) {
            plugin.send(staff, "not-prisoner", Map.of(
                    "player", target.getName() == null ? target.getUniqueId().toString() : target.getName()
            ));
            return false;
        }

        Player online = target.getPlayer();
        if (online != null && online.isOnline()) {
            releaseOnline(online, sentence, staff, true);
            return true;
        }

        sentence.setStatus("RELEASING");
        try {
            repository.updateSentenceStatus(sentence.uuid(), "RELEASING", false);
        } catch (SQLException ex) {
            plugin.databaseError("marcando liberacion pendiente", ex);
            return false;
        }

        plugin.send(staff, "release-pending-offline", Map.of(
                "player", sentence.playerName()
        ));
        return true;
    }

    private void releaseOnline(Player player, Sentence sentence,
                               CommandSender staff, boolean manual) {
        sentence.setStatus("RELEASING");
        try {
            repository.updateSentenceStatus(sentence.uuid(), "RELEASING", false);
        } catch (SQLException ex) {
            plugin.databaseError("preparando liberacion", ex);
            return;
        }

        enforced.remove(player.getUniqueId());
        cancelAuthLock(player.getUniqueId());
        removeBossBar(player);
        cancelActionBar(player.getUniqueId());

        // No volvemos a crear el kit al liberar: se limpia directamente y
        // luego se restaura el snapshot original.
        kitManager.clearForPrison(player);
        sentence.snapshot().restore(player);

        runConsoleCommands("prison.integrations.on-release-console-commands", player);
        showScreenTitle(player, "prison.screen-titles.release");

        Location release = releaseLocation();
        if (release != null) safeTeleport(player, release);

        sentence.setStatus("COMPLETED");
        try {
            repository.updateSentenceStatus(sentence.uuid(), "COMPLETED", true);
        } catch (SQLException ex) {
            plugin.databaseError("finalizando liberacion", ex);
        }

        sentences.remove(player.getUniqueId());

        plugin.send(player, "released-target", Map.of(
                "player", player.getName()
        ));

        if (staff != null) {
            plugin.send(staff, "released-staff", Map.of("player", player.getName()));
        }

        plugin.broadcast("released-broadcast", Map.of("player", player.getName()));
    }

    public void onAuthenticated(Player player) {
        Sentence sentence = sentences.get(player.getUniqueId());
        if (sentence == null) return;

        if ("RELEASING".equals(sentence.status())) {
            releaseOnline(player, sentence, null, true);
            return;
        }

        if (!"ACTIVE".equals(sentence.status())) return;

        enforced.add(player.getUniqueId());
        applyPrisonState(player, false);
        updateBossBar(player);
        startPostAuthLock(player);
    }

    public void onJoinFallback(Player player) {
        if (!sentences.containsKey(player.getUniqueId())) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || isEnforced(player.getUniqueId())) return;

            if (plugin.nLoginBridge() == null || plugin.nLoginBridge().isAuthenticated(player)) {
                onAuthenticated(player);
            }
        }, 40L);
    }

    public void onQuit(Player player) {
        enforced.remove(player.getUniqueId());
        removeBossBar(player);
        cancelActionBar(player.getUniqueId());
        cancelAuthLock(player.getUniqueId());
    }

    private void applyPrisonState(Player player, boolean freshPunishment) {
        player.setGameMode(GameMode.SURVIVAL);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);

        // Primero quitamos el inventario y hacemos el TP. El kit se entrega DESPUES
        // del cambio de mundo para que plugins del lobby (libros, hotbar, etc.)
        // no desplacen la comida y la terminen tirando al piso.
        kitManager.clearForPrison(player);

        Location spawn = prisonSpawn();
        if (spawn != null) safeTeleport(player, spawn);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && isActive(player.getUniqueId())) {
                kitManager.issue(player);
            }
        }, 1L);

        // Segunda pasada corta por compatibilidad con plugins que aplican inventarios
        // unos ticks despues de cambiar de mundo.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && isActive(player.getUniqueId())) {
                kitManager.ensure(player);
            }
        }, 5L);
    }

    private void startKitGuard() {
        if (kitGuardTask != null) kitGuardTask.cancel();

        long interval = Math.max(1L,
                plugin.getConfig().getLong("prison.kit-guard-interval-ticks", 10L));

        kitGuardTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID uuid : enforced) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline() || !isActive(uuid)) continue;
                kitManager.ensure(player);
            }
        }, interval, interval);
    }

    public void ensureInside(Player player) {
        if (!isEnforced(player.getUniqueId())) return;
        if (isInsidePrison(player.getLocation())) return;
        returnToPrison(player, true);
    }

    public void returnToPrison(Player player, boolean notify) {
        if (!isEnforced(player.getUniqueId())) return;
        Location spawn = prisonSpawn();
        if (spawn != null) {
            safeTeleport(player, spawn);
            if (notify) plugin.send(player, "prisoner-escape", Map.of());
        }
    }

    public void safeTeleport(Player player, Location location) {
        internalTeleports.add(player.getUniqueId());
        try {
            player.teleport(location);
        } finally {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> internalTeleports.remove(player.getUniqueId()));
        }
    }

    public boolean isInternalTeleport(UUID uuid) {
        return internalTeleports.contains(uuid);
    }

    private void startPostAuthLock(Player player) {
        cancelAuthLock(player.getUniqueId());

        long total = Math.max(0L,
                plugin.getConfig().getLong("prison.post-auth-lock-ticks", 60L));
        long every = Math.max(1L,
                plugin.getConfig().getLong("prison.post-auth-lock-check-every-ticks", 5L));

        if (total <= 0L) return;

        BukkitTask task = new BukkitRunnable() {
            long elapsed = 0L;

            @Override
            public void run() {
                if (!player.isOnline() || !isActive(player.getUniqueId())) {
                    cancel();
                    authLockTasks.remove(player.getUniqueId());
                    return;
                }

                ensureInside(player);
                elapsed += every;

                if (elapsed >= total) {
                    cancel();
                    authLockTasks.remove(player.getUniqueId());
                }
            }
        }.runTaskTimer(plugin, 1L, every);

        authLockTasks.put(player.getUniqueId(), task);
    }

    private void cancelAuthLock(UUID uuid) {
        BukkitTask old = authLockTasks.remove(uuid);
        if (old != null) old.cancel();
    }

    public void updateBossBar(Player player) {
        if (!plugin.getConfig().getBoolean("prison.progress.bossbar.enabled", true)) return;
        Sentence sentence = sentences.get(player.getUniqueId());
        if (sentence == null || !"ACTIVE".equals(sentence.status())) return;

        BossBar bar = bossBars.computeIfAbsent(player.getUniqueId(), uuid -> {
            BarColor color = parseBarColor(plugin.getConfig()
                    .getString("prison.progress.bossbar.color", "RED"));
            BarStyle style = parseBarStyle(plugin.getConfig()
                    .getString("prison.progress.bossbar.style", "SOLID"));
            BossBar created = Bukkit.createBossBar("", color, style);
            created.addPlayer(player);
            return created;
        });

        if (!bar.getPlayers().contains(player)) bar.addPlayer(player);

        String title = plugin.getConfig().getString(
                "prison.progress.bossbar.title",
                "&7Condena: {current}/{required}");
        title = TextUtil.replace(title, progressTokens(sentence, 0));
        bar.setTitle(TextUtil.color(title));

        double progress = sentence.requiredPoints() <= 0 ? 1.0
                : (double) sentence.currentPoints() / (double) sentence.requiredPoints();
        bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
        bar.setVisible(true);
    }

    private void pulseActionBar(Player player, int gained) {
        if (!plugin.getConfig().getBoolean("prison.progress.actionbar.enabled", true)) return;
        Sentence sentence = sentences.get(player.getUniqueId());
        if (sentence == null) return;

        cancelActionBar(player.getUniqueId());

        int ticks = Math.max(1,
                plugin.getConfig().getInt("prison.progress.actionbar.pulse-ticks", 14));
        String raw = plugin.getConfig().getString(
                "prison.progress.actionbar.message",
                "&6+{gained} &7| {current}/{required}");
        String rendered = TextUtil.replace(raw, progressTokens(sentence, gained));

        BukkitTask task = new BukkitRunnable() {
            int sent = 0;

            @Override
            public void run() {
                if (!player.isOnline() || !isActive(player.getUniqueId())) {
                    cancel();
                    actionBarTasks.remove(player.getUniqueId());
                    return;
                }

                player.sendActionBar(TextUtil.component(rendered));
                sent++;

                if (sent >= ticks) {
                    cancel();
                    actionBarTasks.remove(player.getUniqueId());
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        actionBarTasks.put(player.getUniqueId(), task);
    }

    private Map<String, String> progressTokens(Sentence sentence, int gained) {
        Map<String, String> map = new HashMap<>();
        map.put("player", sentence.playerName());
        map.put("gained", Integer.toString(gained));
        map.put("current", Integer.toString(sentence.currentPoints()));
        map.put("required", Integer.toString(sentence.requiredPoints()));
        map.put("remaining", Integer.toString(sentence.remaining()));
        return map;
    }

    private void removeBossBar(Player player) {
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removeAll();
            bar.setVisible(false);
        }
    }

    private void cancelActionBar(UUID uuid) {
        BukkitTask task = actionBarTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    private void runConsoleCommands(String path, Player player) {
        for (String command : plugin.getConfig().getStringList(path)) {
            if (command == null || command.isBlank()) continue;
            String rendered = command
                    .replace("{player}", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString());
            if (rendered.startsWith("/")) rendered = rendered.substring(1);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rendered);
        }
    }

    private void showScreenTitle(Player player, String path) {
        if (!plugin.getConfig().getBoolean(path + ".enabled", true)) return;

        String title = TextUtil.color(plugin.getConfig()
                .getString(path + ".title", ""));
        String subtitle = TextUtil.color(plugin.getConfig()
                .getString(path + ".subtitle", ""));
        int fadeIn = plugin.getConfig().getInt(path + ".fade-in", 10);
        int stay = plugin.getConfig().getInt(path + ".stay", 60);
        int fadeOut = plugin.getConfig().getInt(path + ".fade-out", 20);

        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
    }

    private BarColor parseBarColor(String raw) {
        try {
            return BarColor.valueOf(raw == null ? "RED" : raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return BarColor.RED;
        }
    }

    private BarStyle parseBarStyle(String raw) {
        try {
            return BarStyle.valueOf(raw == null ? "SOLID" : raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return BarStyle.SOLID;
        }
    }

    public void shutdown() {
        for (BossBar bar : bossBars.values()) {
            bar.removeAll();
            bar.setVisible(false);
        }
        bossBars.clear();

        for (BukkitTask task : actionBarTasks.values()) task.cancel();
        actionBarTasks.clear();

        for (BukkitTask task : authLockTasks.values()) task.cancel();
        authLockTasks.clear();

        if (kitGuardTask != null) {
            kitGuardTask.cancel();
            kitGuardTask = null;
        }
    }
}
