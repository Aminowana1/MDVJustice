package xyz.mdvcraft.justice.prison;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import xyz.mdvcraft.justice.MDVJusticePlugin;
import xyz.mdvcraft.justice.util.TextUtil;

import java.util.ArrayList;
import java.util.List;

public final class PrisonKitManager {
    private final MDVJusticePlugin plugin;
    private final NamespacedKey prisonItemKey;
    private final NamespacedKey prisonItemTypeKey;

    public PrisonKitManager(MDVJusticePlugin plugin) {
        this.plugin = plugin;
        this.prisonItemKey = new NamespacedKey(plugin, "prison_item");
        this.prisonItemTypeKey = new NamespacedKey(plugin, "prison_item_type");
    }

    /**
     * Vacía el inventario del preso antes del TP. El kit real se entrega después
     * del cambio de mundo para evitar que plugins de lobby desplacen/droppeen los ítems.
     */
    public void clearForPrison(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(null);
        player.updateInventory();
    }

    public void issue(Player player) {
        clearForPrison(player);

        PlayerInventory inventory = player.getInventory();

        ConfigurationSection pick = plugin.getConfig().getConfigurationSection("prison.kit.pickaxe");
        if (pick != null) {
            int slot = pickaxeSlot();
            inventory.setItem(slot, createItem(pick, "IRON_PICKAXE", 1, true, "pickaxe"));
            inventory.setHeldItemSlot(slot);
        }

        ConfigurationSection food = plugin.getConfig().getConfigurationSection("prison.kit.food");
        if (food != null) {
            int slot = foodSlot();
            int amount = configuredFoodAmount();
            inventory.setItem(slot, createItem(food, "COOKED_BEEF", amount, false, "food"));
        }

        player.updateInventory();
    }

    /**
     * Repara el kit sin generar drops. Además elimina cualquier objeto externo que
     * otro plugin haya intentado meter en el inventario del preso.
     */
    public void ensure(Player player) {
        PlayerInventory inventory = player.getInventory();
        int pickSlot = pickaxeSlot();
        int foodSlot = foodSlot();

        // Un preso solo puede conservar los dos ítems de su kit.
        for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
            if (slot == pickSlot || slot == foodSlot) continue;
            if (inventory.getItem(slot) != null) {
                inventory.setItem(slot, null);
            }
        }

        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(null);

        ConfigurationSection pick = plugin.getConfig().getConfigurationSection("prison.kit.pickaxe");
        if (pick != null && !isPrisonItemType(inventory.getItem(pickSlot), "pickaxe")) {
            inventory.setItem(pickSlot,
                    createItem(pick, "IRON_PICKAXE", 1, true, "pickaxe"));
        }

        ConfigurationSection food = plugin.getConfig().getConfigurationSection("prison.kit.food");
        if (food != null) {
            ItemStack current = inventory.getItem(foodSlot);
            int targetAmount = configuredFoodAmount();

            if (!isPrisonItemType(current, "food")
                    || current.getAmount() != targetAmount) {
                inventory.setItem(foodSlot,
                        createItem(food, "COOKED_BEEF", targetAmount, false, "food"));
            }
        }

        player.updateInventory();
    }

    public void replenishFood(Player player) {
        ConfigurationSection food = plugin.getConfig().getConfigurationSection("prison.kit.food");
        if (food == null || !food.getBoolean("replenish-after-consume", true)) return;

        int slot = foodSlot();
        int amount = configuredFoodAmount();
        player.getInventory().setItem(
                slot, createItem(food, "COOKED_BEEF", amount, false, "food"));
        player.updateInventory();
    }

    public boolean isPrisonItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer()
                .get(prisonItemKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    public boolean isPrisonItemType(ItemStack item, String type) {
        if (!isPrisonItem(item) || !item.hasItemMeta()) return false;

        String storedType = item.getItemMeta().getPersistentDataContainer()
                .get(prisonItemTypeKey, PersistentDataType.STRING);

        return type.equalsIgnoreCase(storedType == null ? "" : storedType);
    }

    public int pickaxeSlot() {
        ConfigurationSection pick = plugin.getConfig().getConfigurationSection("prison.kit.pickaxe");
        int slot = pick == null ? 0 : pick.getInt("slot", 0);
        return Math.max(0, Math.min(8, slot));
    }

    public int foodSlot() {
        ConfigurationSection food = plugin.getConfig().getConfigurationSection("prison.kit.food");
        int slot = food == null ? 7 : food.getInt("slot", 7);
        slot = Math.max(0, Math.min(8, slot));

        // No permitimos que comida y pico compartan slot.
        if (slot == pickaxeSlot()) {
            slot = pickaxeSlot() == 8 ? 7 : 8;
        }

        return slot;
    }

    private int configuredFoodAmount() {
        ConfigurationSection food = plugin.getConfig().getConfigurationSection("prison.kit.food");
        int amount = food == null ? 64 : food.getInt("amount", 64);
        return Math.max(1, Math.min(64, amount));
    }

    private ItemStack createItem(ConfigurationSection section, String fallback,
                                 int amount, boolean pickaxe, String type) {
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
            meta.getPersistentDataContainer().set(
                    prisonItemTypeKey, PersistentDataType.STRING, type);
            item.setItemMeta(meta);
        }

        return item;
    }
}
