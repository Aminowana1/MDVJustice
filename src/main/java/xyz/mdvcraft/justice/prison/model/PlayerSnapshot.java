package xyz.mdvcraft.justice.prison.model;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.mdvcraft.justice.util.ItemSerializer;

public record PlayerSnapshot(
        String storage,
        String armor,
        String offhand,
        int level,
        float exp,
        int totalExp,
        int food,
        float saturation,
        String gameMode
) {
    public static PlayerSnapshot capture(Player player) {
        return new PlayerSnapshot(
                ItemSerializer.serialize(player.getInventory().getStorageContents()),
                ItemSerializer.serialize(player.getInventory().getArmorContents()),
                ItemSerializer.serialize(new ItemStack[]{player.getInventory().getItemInOffHand()}),
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getGameMode().name()
        );
    }

    public void restore(Player player) {
        player.getInventory().clear();
        player.getInventory().setStorageContents(ItemSerializer.deserialize(storage));
        player.getInventory().setArmorContents(ItemSerializer.deserialize(armor));

        ItemStack[] off = ItemSerializer.deserialize(offhand);
        player.getInventory().setItemInOffHand(off.length == 0 ? null : off[0]);

        player.setLevel(Math.max(0, level));
        player.setExp(Math.max(0.0f, Math.min(1.0f, exp)));
        player.setTotalExperience(Math.max(0, totalExp));
        player.setFoodLevel(Math.max(0, Math.min(20, food)));
        player.setSaturation(Math.max(0.0f, saturation));

        try {
            player.setGameMode(GameMode.valueOf(gameMode));
        } catch (Exception ignored) {
            player.setGameMode(GameMode.SURVIVAL);
        }

        player.updateInventory();
    }
}
