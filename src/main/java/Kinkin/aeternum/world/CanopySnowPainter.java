package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class CanopySnowPainter implements Runnable {

    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;

    private BukkitTask task;
    private final Random random = new Random();

    private boolean enabled;
    private int attemptsPerTick;
    private int radiusBlocks;
    private int maxLeafScanHeight;
    private boolean onlyInColdBiomes;

    // tipos de “suelo” donde permitimos nieve
    private final Set<Material> groundTypes = EnumSet.of(
            Material.GRASS_BLOCK,
            Material.DIRT,
            Material.COARSE_DIRT,
            Material.PODZOL,
            Material.ROOTED_DIRT,
            Material.MYCELIUM,
            Material.SNOW_BLOCK,
            Material.SNOW
    );

    public CanopySnowPainter(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        this.plugin = plugin;
        this.seasons = seasons;
        reloadFromConfig();
    }

    public void reloadFromConfig() {
        var y = plugin.cfg.climate.getConfigurationSection("real_snow.canopy");
        if (y == null) {
            enabled = false;
            return;
        }
        enabled            = y.getBoolean("enabled", true);
        attemptsPerTick    = Math.max(1, y.getInt("attempts_per_tick", 40));
        radiusBlocks       = Math.max(4, y.getInt("radius_blocks", 32));
        maxLeafScanHeight  = Math.max(2, y.getInt("max_leaf_scan_height", 6));
        onlyInColdBiomes   = y.getBoolean("only_in_cold_biomes", true);
    }

    public void register() {
        if (task != null) task.cancel();
        if (!enabled) return;
        // cada 10 ticks (~0.5s)
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this, 60L, 10L);
    }

    public void unregister() {
        if (task != null) task.cancel();
        task = null;
    }

    @Override
    public void run() {
        if (!enabled) return;

        CalendarState st = seasons.getStateCopy();
        if (st.season != Season.WINTER) {
            return; // sólo trabajamos en invierno
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (attemptsPerTick <= 0 || radiusBlocks <= 0) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            World w = p.getWorld();
            if (w.getEnvironment() != World.Environment.NORMAL) continue;

            // 🔹 NUEVO: solo pintar nieve de copa cuando realmente hay tormenta (lluvia/nieve)
            if (!w.hasStorm()) continue;

            int px = p.getLocation().getBlockX();
            int pz = p.getLocation().getBlockZ();
            int minY = w.getMinHeight();
            int maxY = w.getMaxHeight();

            for (int i = 0; i < attemptsPerTick; i++) {
                int x = px + rnd.nextInt(-radiusBlocks, radiusBlocks + 1);
                int z = pz + rnd.nextInt(-radiusBlocks, radiusBlocks + 1);

                // sólo en biomas/chunks fríos si así se configuró
                if (onlyInColdBiomes) {
                    int cx = x >> 4;
                    int cz = z >> 4;

                    boolean markedCold = BiomeSpoofAdapter.isChunkNaturallySnowy(w, cx, cz);
                    boolean biomeCold  = isNaturallySnowyBiome(w, x, z);

                    if (!markedCold && !biomeCold) {
                        continue;
                    }
                }

                int highest = w.getHighestBlockYAt(x, z) - 1; // bloque no aire más alto
                if (highest < minY) continue;

                // buscamos suelo cerca de la superficie (16 bloques hacia abajo máx)
                int scanMinY = Math.max(minY, highest - 16);

                Block ground = null;
                for (int y = highest; y >= scanMinY; y--) {
                    Block candidate = w.getBlockAt(x, y, z);
                    Material t = candidate.getType();

                    // si ya es nieve arriba de todo, no hace falta rellenar
                    if (t == Material.SNOW || t == Material.SNOW_BLOCK) {
                        ground = null;
                        break;
                    }

                    if (groundTypes.contains(t)) {
                        Block above = candidate.getRelative(0, 1, 0);
                        Material aboveType = above.getType();
                        if (aboveType.isAir() || above.isPassable()) {
                            ground = candidate;
                        }
                        break;
                    }
                }

                if (ground == null) continue;

                int groundY = ground.getY();
                Block aboveGround = ground.getRelative(0, 1, 0);
                Material aboveType = aboveGround.getType();
                if (!aboveType.isAir() && !aboveGround.isPassable()) {
                    continue; // ya ocupado
                }

                // tiene que haber hojas haciendo "techo"
                if (!hasLeavesAbove(w, x, groundY + 2, z, maxY)) {
                    continue;
                }

                // colocamos nieve SOLO debajo de árboles y SOLO cuando está nevando
                aboveGround.setType(Material.SNOW, false);
            }
        }
    }


    /**
     * Devuelve true si, entre startY y startY+maxLeafScanHeight,
     * encontramos alguna hoja en la columna, sin topar antes con un bloque sólido "duro".
     */
    private boolean hasLeavesAbove(World w, int x, int startY, int z, int maxWorldY) {
        int maxY = Math.min(startY + maxLeafScanHeight, maxWorldY - 1);
        for (int y = startY; y <= maxY; y++) {
            Block b = w.getBlockAt(x, y, z);
            Material type = b.getType();

            if (isLeaf(type)) {
                return true;
            }

            // Si encontramos un sólido que no es hoja, asumimos que no es techo de árbol
            if (type.isSolid() && !isLeaf(type)) {
                return false;
            }
        }
        return false;
    }

    private boolean isLeaf(Material m) {
        String n = m.name();
        return n.endsWith("_LEAVES");
    }

    private boolean isNaturallySnowyBiome(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z);
        Biome biome = w.getBiome(x, y, z);
        String name = biome.name().toUpperCase();

        return name.contains("SNOW")
                || name.contains("FROZEN")
                || name.contains("ICE")
                || name.contains("TAIGA")
                || name.contains("GROVE")
                || name.contains("PEAK")
                || name.contains("MOUNTAIN");
    }

}
