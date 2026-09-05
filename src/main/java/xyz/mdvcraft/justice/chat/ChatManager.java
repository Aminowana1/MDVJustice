package xyz.mdvcraft.justice.chat;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import xyz.mdvcraft.justice.MDVJusticePlugin;
import xyz.mdvcraft.justice.chat.model.MuteRecord;
import xyz.mdvcraft.justice.chat.model.SlowRecord;
import xyz.mdvcraft.justice.database.JusticeRepository;
import xyz.mdvcraft.justice.util.DurationParser;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ChatManager {
    private final MDVJusticePlugin plugin;
    private final JusticeRepository repository;

    private final Map<UUID, MuteRecord> mutes = new ConcurrentHashMap<>();
    private final Map<UUID, SlowRecord> manualSlows = new ConcurrentHashMap<>();
    private final Map<UUID, SlowRecord> autoSlows = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAcceptedMessageAt = new ConcurrentHashMap<>();
    private final Map<UUID, SpamState> spamStates = new ConcurrentHashMap<>();

    private volatile long globalSlowMillis;

    public ChatManager(MDVJusticePlugin plugin, JusticeRepository repository) throws SQLException {
        this.plugin = plugin;
        this.repository = repository;
        this.mutes.putAll(repository.loadMutes());
        this.manualSlows.putAll(repository.loadSlows("manual_slows"));
        this.autoSlows.putAll(repository.loadSlows("auto_slows"));
        this.globalSlowMillis = repository.loadGlobalSlow();

        if (globalSlowMillis <= 0L) {
            long configured = plugin.durationOrZero(plugin.getConfig()
                    .getString("chat.global-slow.default-delay", "0s"));
            globalSlowMillis = configured;
        }
    }

    public boolean handleChat(Player player, String message) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();

        MuteRecord mute = activeMute(uuid, now);
        if (mute != null) {
            plugin.sendSync(player, "mute-blocked", Map.of(
                    "remaining", mute.permanent()
                            ? plugin.rawMessage("duration-permanent")
                            : DurationParser.format(mute.remaining(now)),
                    "reason", mute.reason()
            ));
            return false;
        }

        long effectiveDelay = effectiveDelay(player, now);
        long previous = lastAcceptedMessageAt.getOrDefault(uuid, 0L);
        long elapsed = now - previous;

        if (effectiveDelay > 0L && elapsed < effectiveDelay) {
            plugin.sendSync(player, "slow-wait", Map.of(
                    "remaining", DurationParser.format(effectiveDelay - elapsed)
            ));
            return false;
        }

        lastAcceptedMessageAt.put(uuid, now);
        inspectSpam(player, message, now);
        return true;
    }

    public long effectiveDelay(Player player, long now) {
        long delay = 0L;

        String globalBypass = plugin.getConfig().getString(
                "permissions.chat-global-bypass", "mdvjustice.chat.global.bypass");
        if (globalSlowMillis > 0L && (globalBypass == null || !player.hasPermission(globalBypass))) {
            delay = globalSlowMillis;
        }

        SlowRecord manual = activeSlow(manualSlows, "manual_slows", player.getUniqueId(), now);
        if (manual != null) delay = Math.max(delay, manual.delayMillis());

        SlowRecord auto = activeSlow(autoSlows, "auto_slows", player.getUniqueId(), now);
        if (auto != null) delay = Math.max(delay, auto.delayMillis());

        if (plugin.prisonManager() != null && plugin.prisonManager().isActive(player.getUniqueId())) {
            delay = Math.max(delay, plugin.prisonManager().prisonChatDelayMillis());
        }

        return delay;
    }

    private void inspectSpam(Player player, String message, long now) {
        if (!plugin.getConfig().getBoolean("chat.antispam.enabled", true)) return;

        String bypass = plugin.getConfig().getString(
                "permissions.antispam-bypass", "mdvjustice.chat.antispam.bypass");
        if (bypass != null && !bypass.isBlank() && player.hasPermission(bypass)) return;

        String normalized = normalize(message);
        SpamState state = spamStates.computeIfAbsent(player.getUniqueId(), ignored -> new SpamState());

        if (normalized.equals(state.lastNormalized)) {
            state.repeated++;
        } else {
            state.lastNormalized = normalized;
            state.repeated = 1;
        }

        int repeatedThreshold = Math.max(2,
                plugin.getConfig().getInt("chat.antispam.repeated-message-threshold", 3));

        long window = Math.max(1,
                plugin.getConfig().getLong("chat.antispam.burst-window-seconds", 4)) * 1000L;
        int burstCount = Math.max(2,
                plugin.getConfig().getInt("chat.antispam.burst-message-count", 5));

        state.timestamps.addLast(now);
        while (!state.timestamps.isEmpty() && now - state.timestamps.peekFirst() > window) {
            state.timestamps.removeFirst();
        }

        if (state.repeated >= repeatedThreshold || state.timestamps.size() >= burstCount) {
            state.repeated = 0;
            state.timestamps.clear();
            applyAntiSpamSlow(player);
        }
    }

    private String normalize(String input) {
        String result = input == null ? "" : input;

        if (plugin.getConfig().getBoolean("chat.antispam.normalize.strip-colors", true)) {
            result = org.bukkit.ChatColor.stripColor(
                    org.bukkit.ChatColor.translateAlternateColorCodes('&', result));
        }
        if (plugin.getConfig().getBoolean("chat.antispam.normalize.lowercase", true)) {
            result = result.toLowerCase(Locale.ROOT);
        }
        if (plugin.getConfig().getBoolean("chat.antispam.normalize.strip-basic-punctuation", true)) {
            result = result.replaceAll("[!¡?¿.,;:_\\-]+", "");
        }
        if (plugin.getConfig().getBoolean("chat.antispam.normalize.collapse-spaces", true)) {
            result = result.trim().replaceAll("\\s+", " ");
        }
        return result;
    }

    private void applyAntiSpamSlow(Player player) {
        long delay = plugin.durationOrZero(
                plugin.getConfig().getString("chat.antispam.punishment-delay", "10s"));
        long duration = plugin.durationOrZero(
                plugin.getConfig().getString("chat.antispam.punishment-duration", "30m"));
        if (delay <= 0 || duration <= 0) return;

        long expires = System.currentTimeMillis() + duration;
        SlowRecord current = autoSlows.get(player.getUniqueId());

        if (current != null && current.active(System.currentTimeMillis())
                && current.delayMillis() >= delay && current.expiresAt() >= expires - 1000L) {
            return;
        }

        SlowRecord record = new SlowRecord(
                player.getUniqueId(), player.getName(), delay, expires);
        autoSlows.put(player.getUniqueId(), record);
        plugin.runSync(() -> {
            try {
                repository.saveSlow("auto_slows", record);
            } catch (SQLException ex) {
                plugin.databaseError("guardando slow automatico", ex);
            }
            plugin.send(player, "antispam-triggered", Map.of(
                    "delay", DurationParser.format(delay),
                    "duration", DurationParser.format(duration)
            ));
        });
    }

    public void setGlobalSlow(long millis) {
        globalSlowMillis = Math.max(0L, millis);
        try {
            repository.saveGlobalSlow(globalSlowMillis);
        } catch (SQLException ex) {
            plugin.databaseError("guardando slow global", ex);
        }
    }

    public long globalSlowMillis() {
        return globalSlowMillis;
    }

    public void applyManualSlow(OfflinePlayer target, long delay, long duration) {
        long expires = duration < 0 ? -1L : System.currentTimeMillis() + duration;
        SlowRecord record = new SlowRecord(
                target.getUniqueId(),
                target.getName() == null ? target.getUniqueId().toString() : target.getName(),
                delay,
                expires
        );
        manualSlows.put(target.getUniqueId(), record);
        try {
            repository.saveSlow("manual_slows", record);
        } catch (SQLException ex) {
            plugin.databaseError("guardando slow individual", ex);
        }
    }

    public boolean removeManualSlow(UUID uuid) {
        SlowRecord removed = manualSlows.remove(uuid);
        try {
            repository.deleteSlow("manual_slows", uuid);
        } catch (SQLException ex) {
            plugin.databaseError("eliminando slow individual", ex);
        }
        return removed != null;
    }

    public void applyMute(OfflinePlayer target, long duration, String reason, String staff) {
        long expires = duration < 0 ? -1L : System.currentTimeMillis() + duration;
        MuteRecord record = new MuteRecord(
                target.getUniqueId(),
                target.getName() == null ? target.getUniqueId().toString() : target.getName(),
                expires,
                reason,
                staff
        );
        mutes.put(target.getUniqueId(), record);
        try {
            repository.saveMute(record);
        } catch (SQLException ex) {
            plugin.databaseError("guardando mute", ex);
        }
    }

    public boolean removeMute(UUID uuid) {
        MuteRecord removed = mutes.remove(uuid);
        try {
            repository.deleteMute(uuid);
        } catch (SQLException ex) {
            plugin.databaseError("eliminando mute", ex);
        }
        return removed != null;
    }

    public MuteRecord activeMute(UUID uuid, long now) {
        MuteRecord record = mutes.get(uuid);
        if (record == null) return null;
        if (record.active(now)) return record;

        mutes.remove(uuid);
        plugin.runSync(() -> {
            try {
                repository.deleteMute(uuid);
            } catch (SQLException ex) {
                plugin.databaseError("limpiando mute expirado", ex);
            }
        });
        return null;
    }

    private SlowRecord activeSlow(Map<UUID, SlowRecord> map, String table, UUID uuid, long now) {
        SlowRecord record = map.get(uuid);
        if (record == null) return null;
        if (record.active(now)) return record;

        map.remove(uuid);
        plugin.runSync(() -> {
            try {
                repository.deleteSlow(table, uuid);
            } catch (SQLException ex) {
                plugin.databaseError("limpiando slow expirado", ex);
            }
        });
        return null;
    }

    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        for (UUID uuid : new ArrayList<>(mutes.keySet())) activeMute(uuid, now);
        for (UUID uuid : new ArrayList<>(manualSlows.keySet())) activeSlow(manualSlows, "manual_slows", uuid, now);
        for (UUID uuid : new ArrayList<>(autoSlows.keySet())) activeSlow(autoSlows, "auto_slows", uuid, now);
    }

    private static final class SpamState {
        private String lastNormalized = "";
        private int repeated = 0;
        private final Deque<Long> timestamps = new ArrayDeque<>();
    }
}
