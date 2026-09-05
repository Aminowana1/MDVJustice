package xyz.mdvcraft.justice.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

public final class ItemSerializer {
    private ItemSerializer() {
    }

    public static String serialize(ItemStack[] items) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
                out.writeInt(items == null ? 0 : items.length);
                if (items != null) {
                    for (ItemStack item : items) {
                        out.writeObject(item);
                    }
                }
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo serializar el inventario", ex);
        }
    }

    public static ItemStack[] deserialize(String data) {
        if (data == null || data.isBlank()) return new ItemStack[0];
        try {
            byte[] raw = Base64.getDecoder().decode(data);
            try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(raw))) {
                int size = in.readInt();
                ItemStack[] items = new ItemStack[size];
                for (int i = 0; i < size; i++) {
                    items[i] = (ItemStack) in.readObject();
                }
                return items;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo deserializar el inventario", ex);
        }
    }
}
