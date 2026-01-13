package Kinkin.aeternum;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public final class SeasonGuideService implements Listener {

    private final AeternumSeasonsPlugin plugin;
    private final File file;
    private final YamlConfiguration data;

    public SeasonGuideService(AeternumSeasonsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/players.yml");
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        if (!file.exists()) {
            this.data = new YamlConfiguration();
            save();
        } else {
            this.data = YamlConfiguration.loadConfiguration(file);
        }
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        save();
    }

    private boolean hasSeenGuide(UUID id) {
        return data.getBoolean("players." + id + ".seen_guide", false);
    }

    private void markSeenGuide(UUID id) {
        data.set("players." + id + ".seen_guide", true);
        save();
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar players.yml: " + e.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (hasSeenGuide(p.getUniqueId())) return;

        // Lo marcamos ya para que no se repita aunque se desconecte
        markSeenGuide(p.getUniqueId());

        plugin.getScheduler().runLater(() -> {
            if (!p.isOnline()) return;
            SeasonGuide.sendGuide(p, plugin);
        }, 60L); // ~3 segundos
    }
}
