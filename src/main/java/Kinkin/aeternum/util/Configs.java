package Kinkin.aeternum.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;

public final class Configs {
    private final Plugin plugin;

    public FileConfiguration calendar, hud, climate, survival;

    public Configs(Plugin plugin) { this.plugin = plugin; }

    public void loadAll() {
        calendar = load("calendar.yml");
        hud      = load("hud.yml");
        climate  = load("climate.yml");
    }

    public void saveAll() {
        save("calendar.yml", calendar);
        save("hud.yml", hud);
        save("climate.yml", climate);
    }

    private FileConfiguration load(String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists()) plugin.saveResource(name, false);
        return YamlConfiguration.loadConfiguration(f);
    }

    private void save(String name, FileConfiguration cfg) {
        try { cfg.save(new File(plugin.getDataFolder(), name)); }
        catch (IOException ignored) {}
    }
}
