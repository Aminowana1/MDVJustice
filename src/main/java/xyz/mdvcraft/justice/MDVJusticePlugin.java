package xyz.mdvcraft.justice;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.mdvcraft.justice.chat.ChatListener;
import xyz.mdvcraft.justice.chat.ChatManager;
import xyz.mdvcraft.justice.command.JusticeCommand;
import xyz.mdvcraft.justice.command.PunishCommand;
import xyz.mdvcraft.justice.command.ReleaseCommand;
import xyz.mdvcraft.justice.database.JusticeRepository;
import xyz.mdvcraft.justice.integration.NLoginBridge;
import xyz.mdvcraft.justice.prison.MineManager;
import xyz.mdvcraft.justice.prison.PrisonListener;
import xyz.mdvcraft.justice.prison.PrisonManager;
import xyz.mdvcraft.justice.util.DurationParser;
import xyz.mdvcraft.justice.util.TextUtil;

import java.io.File;
import java.sql.SQLException;
import java.util.*;

public final class MDVJusticePlugin extends JavaPlugin {
    private JusticeRepository repository;
    private ChatManager chatManager;
    private PrisonManager prisonManager;
    private MineManager mineManager;
    private NLoginBridge nLoginBridge;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            File databaseFile = new File(getDataFolder(),
                    getConfig().getString("database.file", "justice.db"));
            repository = new JusticeRepository(databaseFile);
            chatManager = new ChatManager(this, repository);
            prisonManager = new PrisonManager(this, repository);
            mineManager = new MineManager(this);
        } catch (Exception ex) {
            getLogger().severe("No se pudo iniciar MDVJustice: " + ex.getMessage());
            ex.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(
                new ChatListener(chatManager), this);
        getServer().getPluginManager().registerEvents(
                new PrisonListener(this, prisonManager, mineManager), this);

        if (getServer().getPluginManager().getPlugin("nLogin") != null) {
            try {
                nLoginBridge = new NLoginBridge(this, prisonManager);
                getServer().getPluginManager().registerEvents(nLoginBridge, this);
                getLogger().info("Integracion con nLogin habilitada.");
            } catch (Throwable throwable) {
                getLogger().warning("No se pudo habilitar la integracion nLogin: "
                        + throwable.getMessage());
            }
        }

        JusticeCommand justiceCommand = new JusticeCommand(
                this, chatManager, prisonManager, mineManager);

        Objects.requireNonNull(getCommand("justice")).setExecutor(justiceCommand);
        Objects.requireNonNull(getCommand("justice")).setTabCompleter(justiceCommand);

        PunishCommand punishCommand = new PunishCommand(this, prisonManager);
        Objects.requireNonNull(getCommand("castigar")).setExecutor(punishCommand);
        Objects.requireNonNull(getCommand("castigar")).setTabCompleter(punishCommand);

        ReleaseCommand releaseCommand = new ReleaseCommand(this, prisonManager);
        Objects.requireNonNull(getCommand("liberar")).setExecutor(releaseCommand);
        Objects.requireNonNull(getCommand("liberar")).setTabCompleter(releaseCommand);

        getServer().getScheduler().runTaskTimer(
                this, chatManager::cleanupExpired, 1200L, 1200L);

        getLogger().info("MDVJustice " + getDescription().getVersion() + " habilitado.");
    }

    @Override
    public void onDisable() {
        if (mineManager != null) mineManager.shutdown();
        if (prisonManager != null) prisonManager.shutdown();

        if (repository != null) {
            try {
                repository.close();
            } catch (SQLException ex) {
                getLogger().warning("No se pudo cerrar justice.db: " + ex.getMessage());
            }
        }
    }

    public ChatManager chatManager() {
        return chatManager;
    }

    public PrisonManager prisonManager() {
        return prisonManager;
    }

    public MineManager mineManager() {
        return mineManager;
    }

    public NLoginBridge nLoginBridge() {
        return nLoginBridge;
    }

    public boolean isAdmin(CommandSender sender) {
        if (sender == null) return false;
        String permission = getConfig().getString(
                "permissions.admin", "mdvjustice.admin");
        return sender.hasPermission(permission);
    }

    public long durationOrZero(String value) {
        return DurationParser.parseMillis(value).orElse(0L);
    }

    public String rawMessage(String key) {
        return getConfig().getString("messages." + key, "");
    }

    public String renderedMessage(String key, Map<String, String> values) {
        String prefix = getConfig().getString("messages.prefix", "");
        String raw = getConfig().getString("messages." + key, key);

        Map<String, String> all = new HashMap<>();
        if (values != null) all.putAll(values);
        all.put("prefix", prefix == null ? "" : prefix);

        return TextUtil.color(TextUtil.replace(raw, all));
    }

    public void send(CommandSender sender, String key, Map<String, String> values) {
        if (sender == null) return;
        String text = renderedMessage(key, values);
        if (!text.isBlank()) sender.sendMessage(text);
    }

    public void sendSync(Player player, String key, Map<String, String> values) {
        runSync(() -> {
            if (player.isOnline()) send(player, key, values);
        });
    }

    public void broadcast(String key, Map<String, String> values) {
        String text = renderedMessage(key, values);
        if (text.isBlank()) return;
        Bukkit.broadcastMessage(text);
    }

    public void runSync(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            getServer().getScheduler().runTask(this, runnable);
        }
    }

    public OfflinePlayer findKnownPlayer(String name) {
        if (name == null || name.isBlank()) return null;

        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;

        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            String offlineName = offline.getName();
            if (offlineName != null && offlineName.equalsIgnoreCase(name)) {
                return offline;
            }
        }
        return null;
    }

    public void databaseError(String operation, Exception ex) {
        getLogger().severe("Error SQLite " + operation + ": " + ex.getMessage());
        ex.printStackTrace();
    }
}
