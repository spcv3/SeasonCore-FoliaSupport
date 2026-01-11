package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;

// WorldGuard 7+
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;

import com.sk89q.worldguard.protection.ApplicableRegionSet;

import com.sk89q.worldguard.protection.managers.RegionManager;

public final class WinterWorldGuardHelper {

    private static boolean hooked = false;
    private static boolean respectRegions = false;

    private WinterWorldGuardHelper() {}

    public static void init(AeternumSeasonsPlugin plugin) {
        // Leer config
        respectRegions = plugin.cfg != null && plugin.cfg.climate != null
                ? plugin.cfg.climate.getBoolean("real_snow.worldguard_respect_regions",
                plugin.getConfig().getBoolean("winter_painter.worldguard_respect_regions", false))
                : plugin.getConfig().getBoolean("winter_painter.worldguard_respect_regions", false);
// Ver si WorldGuard está cargado
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            hooked = true;
            plugin.getLogger().info("[AeternumSeasons] WorldGuard detected, snow painter will respect regions: " + respectRegions);
        } else {
            hooked = false;
        }
    }

    /**
     * Devuelve true si PODEMOS modificar este bloque (poner o quitar nieve/hielo).
     * - Si no hay WorldGuard o respeto desactivado -> siempre true.
     * - Si hay WorldGuard y la ubicación está dentro de alguna región -> false.
     */
    public static boolean canModify(Block block) {
        if (!hooked || !respectRegions || block == null) {
            return true;
        }
        Location loc = block.getLocation();
        if (loc.getWorld() == null) return true;

        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager manager = container.get(BukkitAdapter.adapt(loc.getWorld()));
        if (manager == null) return true;

        BlockVector3 vec = BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        ApplicableRegionSet set = manager.getApplicableRegions(vec);

        // Si hay alguna región, no tocamos este bloque
        return set == null || set.size() == 0;
    }
}
