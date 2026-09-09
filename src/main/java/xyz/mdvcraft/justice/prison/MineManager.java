package xyz.mdvcraft.justice.prison;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import xyz.mdvcraft.justice.MDVJusticePlugin;
import xyz.mdvcraft.justice.prison.model.Cuboid;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public final class MineManager {
    private final MDVJusticePlugin plugin;
    private final List<MineBlockDef> definitions = new ArrayList<>();
    private BukkitTask intervalTask;
    private boolean regenerationRunning;

    public MineManager(MDVJusticePlugin plugin) {
        this.plugin = plugin;
        reload();
        startScheduler();
    }

    public void reload() {
        definitions.clear();

        ConfigurationSection blocks = plugin.getConfig().getConfigurationSection("mine.blocks");
        if (blocks == null) return;

        for (String key : blocks.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null || material.isAir() || !material.isBlock()) {
                plugin.getLogger().warning("mine.blocks." + key
                        + " ignorado: no es un bloque valido de Minecraft.");
                continue;
            }

            ConfigurationSection section = blocks.getConfigurationSection(key);
            if (section == null) continue;

            double weight = Math.max(0.0, section.getDouble("weight", 0.0));
            int points = Math.max(0, section.getInt("points", 0));

            if (weight <= 0.0 || points <= 0) {
                plugin.getLogger().warning("mine.blocks." + key
                        + " ignorado: weight y points deben ser mayores a 0.");
                continue;
            }

            definitions.add(new MineBlockDef(material, weight, points));
        }
    }

    public void startScheduler() {
        if (intervalTask != null) {
            intervalTask.cancel();
            intervalTask = null;
        }

        if (!plugin.getConfig().getBoolean("mine.regeneration.enabled", true)) return;

        long seconds = Math.max(1,
                plugin.getConfig().getLong("mine.regeneration.interval-seconds", 30));
        long ticks = seconds * 20L;

        intervalTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::regenerate, ticks, ticks);
    }

    public void shutdown() {
        if (intervalTask != null) intervalTask.cancel();
    }

    /**
     * A partir de 1.0.1 una mina solamente existe cuando AMBAS posiciones
     * fueron confirmadas expresamente mediante los comandos.
     */
    public Cuboid region() {
        ConfigurationSection section =
                plugin.getConfig().getConfigurationSection("mine.region");
        if (section == null) return null;

        if (!section.getBoolean("configured", false)
                || !section.getBoolean("pos1-set", false)
                || !section.getBoolean("pos2-set", false)) {
            return null;
        }

        return Cuboid.from(section);
    }

    public boolean contains(org.bukkit.Location location) {
        Cuboid cuboid = region();
        return cuboid != null && cuboid.contains(location);
    }

    /**
     * Funciona con cualquier bloque configurado: STONE, DIORITE, OBSIDIAN,
     * ores, etc. No existe una lista cerrada de minerales.
     */
    public int pointsFor(Material material) {
        for (MineBlockDef def : definitions) {
            if (def.material() == material) return def.points();
        }
        return 0;
    }

    public boolean isRegenerationRunning() {
        return regenerationRunning;
    }

    public long maxVolume() {
        return Math.max(1L,
                plugin.getConfig().getLong("mine.regeneration.max-volume", 100000L));
    }

    public RegenerationResult regenerate() {
        if (regenerationRunning) return RegenerationResult.BUSY;

        Cuboid cuboid = region();
        if (cuboid == null) return RegenerationResult.NOT_CONFIGURED;

        if (definitions.isEmpty()) return RegenerationResult.NO_VALID_BLOCKS;

        long volume = cuboid.volume();
        long maxVolume = maxVolume();
        if (volume > maxVolume) {
            plugin.getLogger().severe("Regeneracion de mina abortada por seguridad: "
                    + volume + " bloques supera el maximo configurado de " + maxVolume + ".");
            return RegenerationResult.TOO_LARGE;
        }

        World world = Bukkit.getWorld(cuboid.worldName());
        if (world == null) return RegenerationResult.WORLD_UNAVAILABLE;

        regenerationRunning = true;

        int batch = Math.max(1,
                plugin.getConfig().getInt("mine.regeneration.blocks-per-tick", 80));
        boolean skipOccupied = plugin.getConfig().getBoolean(
                "mine.regeneration.skip-player-occupied-blocks", true);

        new BukkitRunnable() {
            private int x = cuboid.minX();
            private int y = cuboid.minY();
            private int z = cuboid.minZ();
            private boolean finished = false;

            @Override
            public void run() {
                int processed = 0;

                while (!finished && processed < batch) {
                    Block block = world.getBlockAt(x, y, z);
                    processed++;

                    if (block.getType().isAir()) {
                        if (!skipOccupied || !occupiedByPlayer(block)) {
                            block.setType(randomMaterial(), false);
                        }
                    }

                    advance();
                }

                if (finished) {
                    regenerationRunning = false;
                    cancel();
                }
            }

            private void advance() {
                z++;
                if (z > cuboid.maxZ()) {
                    z = cuboid.minZ();
                    y++;

                    if (y > cuboid.maxY()) {
                        y = cuboid.minY();
                        x++;

                        if (x > cuboid.maxX()) {
                            finished = true;
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return RegenerationResult.STARTED;
    }

    private boolean occupiedByPlayer(Block block) {
        BoundingBox blockBox = BoundingBox.of(block);

        for (Player player : block.getWorld().getPlayers()) {
            if (!player.isOnline()) continue;
            if (player.getBoundingBox().overlaps(blockBox)) return true;
        }

        return false;
    }

    private Material randomMaterial() {
        double total = 0.0;
        for (MineBlockDef def : definitions) total += def.weight();

        double roll = ThreadLocalRandom.current().nextDouble(total);
        for (MineBlockDef def : definitions) {
            roll -= def.weight();
            if (roll <= 0.0) return def.material();
        }

        return definitions.get(0).material();
    }

    public enum RegenerationResult {
        STARTED,
        BUSY,
        NOT_CONFIGURED,
        TOO_LARGE,
        NO_VALID_BLOCKS,
        WORLD_UNAVAILABLE
    }

    private record MineBlockDef(Material material, double weight, int points) {
    }
}
