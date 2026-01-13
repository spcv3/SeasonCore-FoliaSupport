package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.EnumSet;
import java.util.Set;

public final class FastLeafDecayService implements Listener {

    private final AeternumSeasonsPlugin plugin;

    // Troncos que consideramos "madera de árbol"
    private final Set<Material> logTypes = EnumSet.of(
            Material.OAK_LOG,
            Material.SPRUCE_LOG,
            Material.BIRCH_LOG,
            Material.DARK_OAK_LOG,
            Material.JUNGLE_LOG,
            Material.ACACIA_LOG,
            Material.CHERRY_LOG,
            Material.MANGROVE_LOG
    );

    // Hojas (incluye las pintadas de otoño, que son ACACIA_LEAVES)
    private final Set<Material> leafTypes = EnumSet.of(
            Material.OAK_LEAVES,
            Material.SPRUCE_LEAVES,
            Material.BIRCH_LEAVES,
            Material.DARK_OAK_LEAVES,
            Material.JUNGLE_LEAVES,
            Material.ACACIA_LEAVES,
            Material.CHERRY_LEAVES,
            Material.MANGROVE_LEAVES
    );

    public FastLeafDecayService(AeternumSeasonsPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void unregister() {
        BlockBreakEvent.getHandlerList().unregister(this);
    }

    /**
     * Cada vez que se rompe un tronco, aceleramos el decay de las hojas
     * que ya no tienen ningún tronco cerca. NO tocamos hojas que aún
     * están conectadas a madera.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onLogBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!logTypes.contains(block.getType())) return;

        // Lo hacemos unos ticks después para que Minecraft actualice distancias, etc.
        plugin.getScheduler().runAtLocationLater(block.getLocation(), () -> fastDecayAround(block), 5L);
    }

    private void fastDecayAround(Block originLog) {
        World w = originLog.getWorld();
        if (w == null) return;

        int radius = 6; // radio alrededor del tronco
        int ox = originLog.getX();
        int oy = originLog.getY();
        int oz = originLog.getZ();

        for (int x = ox - radius; x <= ox + radius; x++) {
            for (int y = oy - radius; y <= oy + radius; y++) {
                for (int z = oz - radius; z <= oz + radius; z++) {
                    Block leaf = w.getBlockAt(x, y, z);
                    if (!leafTypes.contains(leaf.getType())) continue;

                    // Si todavía tiene algún tronco cerca, dejamos que Minecraft
                    // haga su decay normal.
                    if (hasLogNearby(leaf)) continue;

                    // Aquí SÍ está "flotando": aceleramos el decay vanilla.
                    // Para que no sea ultra instantáneo, metemos una probabilidad.
                    if (Math.random() < 0.8) { // ~35% de las hojas flotantes se rompen ya
                        leaf.breakNaturally();   // respeta drops vanilla, fortune, etc.
                    }
                }
            }
        }
    }

    /**
     * Comprueba si esta hoja tiene algún tronco cerca (parecido a la
     * lógica de distancia de las hojas vanilla).
     */
    private boolean hasLogNearby(Block leaf) {
        World w = leaf.getWorld();
        int radius = 5; // similar al alcance vanilla
        int lx = leaf.getX();
        int ly = leaf.getY();
        int lz = leaf.getZ();

        for (int x = lx - radius; x <= lx + radius; x++) {
            for (int y = ly - radius; y <= ly + radius; y++) {
                for (int z = lz - radius; z <= lz + radius; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (logTypes.contains(b.getType())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
