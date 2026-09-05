package xyz.mdvcraft.justice.chat;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public final class ChatListener implements Listener {
    private final ChatManager chatManager;

    public ChatListener(ChatManager chatManager) {
        this.chatManager = chatManager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!chatManager.handleChat(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
        }
    }
}
