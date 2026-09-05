package xyz.mdvcraft.justice.prison;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import xyz.mdvcraft.justice.MDVJusticePlugin;
import xyz.mdvcraft.justice.util.TextUtil;

import java.util.ArrayList;
import java.util.List;

public final class PrisonKitManager {
    private final MDVJusticePlugin plugin;
    private final NamespacedKey prisonItemKey;

    public PrisonKitManager(MDVJusticePlugin plugin) {
        this.plugin = plugin;
        this.prisonItemKey = new NamespacedKey(plugin, "prison_item");
    }

    public void issue(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);

        ConfigurationSection pick = plugin.getConfig().getConfigurationSection("prison.kit.pickaxe");
        if (pick != null) {
            int slot = Math.max(0, Math.min(35, pick.getInt("slot", 0)));
            player.getInventory().setItem(slot, createItem(pick, "IRON_PICKAXE", 1, true));
        }

        ConfigurationSection food = plugin.getConfig().getConfigurationSection("prison.kit.food");
        if (food != null) {
            int slot = Math.max(0, Math.min(35, food.getInt("slot", 8)));
            int amount = Math.max(1, Math.min(64, food.getInt("amount", 64)));
            player.getInventory().setItem(slot, createItem(food, "COOKED_BEEF", amount, false));
        }

        player.updateInventory();
    }

    public void replenishFood(Player player) {
        ConfigurationSection food = plugin.getConfig().getConfigurationSection("prison.kit.food");
        if (food == null || !food.getBoolean("replenish-after-consume", true)) return;

        int slot = Math.max(0, Math.min(35, food.getInt("slot", 8)));
        int amount = Math.max(1, Math.min(64, food.getInt("amount", 64)));
        player.getInventory().setItem(slot, createItem(food, "COOKED_BEEF", amount, false));
        player.updateInventory();
    }

    public boolean isPrisonItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer()
                .get(prisonItemKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    private ItemStack createItem(ConfigurationSection section, String fallback,
                                 int amount, boolean pickaxe) {
        Material material = Material.matchMaterial(section.getString("material", fallback));
        if (material == null) material = Material.matchMaterial(fallback);

        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = section.getString("name", "");
            if (name != null && !name.isBlank()) {
                meta.setDisplayName(TextUtil.color(name));
            }

            List<String> lore = new ArrayList<>();
            for (String line : section.getStringList("lore")) {
                lore.add(TextUtil.color(line));
            }
            if (!lore.isEmpty()) meta.setLore(lore);

            if (pickaxe && section.getBoolean("unbreakable", true)) {
                meta.setUnbreakable(true);
                meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            }

            meta.getPersistentDataContainer().set(
                    prisonItemKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }
}
