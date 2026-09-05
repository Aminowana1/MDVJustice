package xyz.mdvcraft.justice.prison.model;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

public record Cuboid(String worldName, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Cuboid from(ConfigurationSection section) {
        if (section == null) return null;
        String world = section.getString("world", "");
        if (world == null || world.isBlank()) return null;

        ConfigurationSection p1 = section.getConfigurationSection("pos1");
        ConfigurationSection p2 = section.getConfigurationSection("pos2");
        if (p1 == null || p2 == null) return null;

        int x1 = p1.getInt("x");
        int y1 = p1.getInt("y");
        int z1 = p1.getInt("z");
        int x2 = p2.getInt("x");
        int y2 = p2.getInt("y");
        int z2 = p2.getInt("z");

        return new Cuboid(
                world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)
        );
    }

    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null) return false;
        if (!location.getWorld().getName().equalsIgnoreCase(worldName)) return false;

        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();

        return x >= minX && x < maxX + 1.0
                && y >= minY && y < maxY + 1.0
                && z >= minZ && z < maxZ + 1.0;
    }

    public long volume() {
        return (long) (maxX - minX + 1)
                * (maxY - minY + 1)
                * (maxZ - minZ + 1);
    }
}
