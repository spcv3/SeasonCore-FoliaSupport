package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.Set;

public final class BiomeSpoofSpawnGuard implements Listener {

    private final AeternumSeasonsPlugin plugin;
    private final BiomeSpoofAdapter spoof;
    private boolean enabled = true;

    private static final Set<Biome> OCELOT_OK = Set.of(
            Biome.JUNGLE, Biome.SPARSE_JUNGLE, Biome.BAMBOO_JUNGLE
    );

    public BiomeSpoofSpawnGuard(AeternumSeasonsPlugin plugin, BiomeSpoofAdapter spoof) {
        this.plugin = plugin;
        this.spoof = spoof;
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (!enabled) return;

        if (e.getEntityType() != EntityType.OCELOT) return;

        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.NATURAL
                && r != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) {
            return;
        }

        World w = e.getLocation().getWorld();
        if (w == null) return;

        int x = e.getLocation().getBlockX();
        int y = e.getLocation().getBlockY();
        int z = e.getLocation().getBlockZ();

        Biome original = spoof.getOriginalBiomeApproxOrNull(w, x, y, z);

// si no sabemos el original (sin backup), BLOQUEAMOS (esto evita sabana = jungle)
        if (original == null) {
            e.setCancelled(true);
            return;
        }

        if (!OCELOT_OK.contains(original)) {
            e.setCancelled(true);
        }
    }
}
