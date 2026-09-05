package xyz.mdvcraft.justice.prison;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import xyz.mdvcraft.justice.MDVJusticePlugin;

import java.util.Locale;
import java.util.Map;

public final class PrisonListener implements Listener {
    private final MDVJusticePlugin plugin;
    private final PrisonManager prison;
    private final MineManager mine;

    public PrisonListener(MDVJusticePlugin plugin, PrisonManager prison, MineManager mine) {
        this.plugin = plugin;
        this.prison = prison;
        this.mine = mine;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        prison.onJoinFallback(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        prison.onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!prison.isActive(player.getUniqueId())) return;

        String raw = event.getMessage();
        if (raw.startsWith("/")) raw = raw.substring(1);
        String root = raw.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (root.contains(":")) root = root.substring(root.indexOf(':') + 1);

        for (String allowed : plugin.getConfig().getStringList("prison.allowed-auth-commands")) {
            if (root.equalsIgnoreCase(allowed)) return;
        }

        event.setCancelled(true);
        plugin.send(player, "prisoner-command-blocked", Map.of());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!prison.isEnforced(player.getUniqueId()) || event.getTo() == null) return;

        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        if (!prison.isInsidePrison(event.getTo())) {
            event.setCancelled(true);
            prison.returnToPrison(player, true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!prison.isEnforced(player.getUniqueId())) return;
        if (prison.isInternalTeleport(player.getUniqueId())) return;
        if (event.getTo() != null && prison.isInsidePrison(event.getTo())) return;

        event.setCancelled(true);
        plugin.send(player, "prisoner-escape", Map.of());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        boolean prisoner = prison.isEnforced(player.getUniqueId());
        boolean insideMine = mine.contains(event.getBlock().getLocation());

        if (insideMine && !prisoner) {
            event.setCancelled(true);
            plugin.send(player, "normal-player-mine-break", Map.of());
            return;
        }

        if (!prisoner) return;

        if (!insideMine) {
            event.setCancelled(true);
            plugin.send(player, "prisoner-no-break", Map.of());
            return;
        }

        int points = mine.pointsFor(event.getBlock().getType());
        if (points <= 0) {
            event.setCancelled(true);
            return;
        }

        event.setDropItems(false);
        event.setExpToDrop(0);
        prison.addProgress(player, points);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!prison.isEnforced(event.getPlayer().getUniqueId())) return;
        if (!plugin.getConfig().getBoolean("prison.restrictions.prevent-block-place", true)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!prison.isEnforced(event.getPlayer().getUniqueId())) return;
        if (!plugin.getConfig().getBoolean("prison.restrictions.prevent-item-drop", true)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!prison.isEnforced(player.getUniqueId())) return;
        if (!plugin.getConfig().getBoolean("prison.restrictions.prevent-item-pickup", true)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!prison.isEnforced(player.getUniqueId())) return;
        if (!plugin.getConfig().getBoolean("prison.restrictions.prevent-container-open", true)) return;

        event.setCancelled(true);
        plugin.send(player, "container-blocked", Map.of());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!prison.isEnforced(player.getUniqueId())) return;
        if (!prison.kitManager().isPrisonItem(event.getItem())) return;

        plugin.getServer().getScheduler().runTask(plugin,
                () -> prison.kitManager().replenishFood(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Player damager = attackingPlayer(event.getDamager());
        if (damager != null && prison.isEnforced(damager.getUniqueId())
                && plugin.getConfig().getBoolean("prison.restrictions.prevent-outgoing-damage", true)) {
            event.setCancelled(true);
            return;
        }

        if (event.getEntity() instanceof Player victim
                && prison.isEnforced(victim.getUniqueId())
                && damager != null
                && plugin.getConfig().getBoolean("prison.restrictions.prevent-incoming-pvp", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!prison.isEnforced(player.getUniqueId())) return;
        if (!plugin.getConfig().getBoolean("prison.restrictions.prevent-death", true)) return;

        if (event.getFinalDamage() >= player.getHealth()) {
            event.setCancelled(true);
            player.setHealth(Math.max(1.0, player.getMaxHealth()));
            prison.ensureInside(player);
        }
    }

    private Player attackingPlayer(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}
