package xyz.mdvcraft.justice.integration;

import com.nickuc.login.api.nLoginAPI;
import com.nickuc.login.api.event.bukkit.auth.AuthenticateEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import xyz.mdvcraft.justice.MDVJusticePlugin;
import xyz.mdvcraft.justice.prison.PrisonManager;

public final class NLoginBridge implements Listener {
    private final MDVJusticePlugin plugin;
    private final PrisonManager prisonManager;
    private final nLoginAPI api;

    public NLoginBridge(MDVJusticePlugin plugin, PrisonManager prisonManager) {
        this.plugin = plugin;
        this.prisonManager = prisonManager;
        this.api = nLoginAPI.getApi();
    }

    @EventHandler
    public void onAuthenticate(AuthenticateEvent event) {
        Player player = event.getPlayer();
        if (!prisonManager.isOpenSentence(player.getUniqueId())) return;

        long delay = 1L;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) prisonManager.onAuthenticated(player);
        }, delay);
    }

    public boolean isAuthenticated(Player player) {
        try {
            return api.isAuthenticated(player.getName());
        } catch (Throwable ignored) {
            return false;
        }
    }
}
