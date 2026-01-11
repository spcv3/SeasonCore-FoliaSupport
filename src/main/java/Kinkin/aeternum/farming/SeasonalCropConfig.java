package Kinkin.aeternum.farming;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.Season;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

public final class SeasonalCropConfig {

    private final AeternumSeasonsPlugin plugin;

    private boolean enabled;

    // Crecimiento general
    private double rainBonus;                // +X velocidad cuando llueve al aire libre
    private double winterGreenhouseBonus;    // +X velocidad en invierno dentro de invernadero
    private double undergroundMultiplier;    // multiplicador de velocidad bajo tierra (0.3 = 30%)
    private double lowLightMultiplier;       // multiplicador si hay poca luz
    private int requiredLight;               // luz mínima recomendada

    // Invernaderos
    private boolean greenhouseEnabled;
    private Set<Material> greenhouseBlocks;
    private int maxRoofHeight;
    private int greenhouseRadius;
    private int greenhouseMinGlass;
    private boolean greenhouseRequireCore;
    private Material greenhouseCoreBlock;

    // Debug
    private boolean debugEnabled;
    private Material debugStickMaterial;

    // Estaciones permitidas por cultivo
    private final Map<Material, EnumSet<Season>> cropSeasons = new HashMap<>();

    public SeasonalCropConfig(AeternumSeasonsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File f = new File(plugin.getDataFolder(), "crops.yml");
        if (!f.exists()) {
            try {
                plugin.saveResource("crops.yml", false);
            } catch (IllegalArgumentException ignored) {
            }
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);

        enabled = cfg.getBoolean("seasonal_crops.enabled", true);

        rainBonus = cfg.getDouble("seasonal_crops.rain_growth_bonus", 1.0D);
        winterGreenhouseBonus = cfg.getDouble("seasonal_crops.greenhouse.winter_bonus", 1.0D);

        undergroundMultiplier = cfg.getDouble("seasonal_crops.underground_slow_multiplier", 0.3D);
        lowLightMultiplier = cfg.getDouble("seasonal_crops.low_light_multiplier", 0.5D);
        requiredLight = cfg.getInt("seasonal_crops.required_light", 9);

        // Config invernadero
        greenhouseEnabled = cfg.getBoolean("seasonal_crops.greenhouse.enabled", true);
        maxRoofHeight = cfg.getInt("seasonal_crops.greenhouse.max_roof_height", 8);
        greenhouseRadius = cfg.getInt("seasonal_crops.greenhouse.radius", 7);
        greenhouseMinGlass = cfg.getInt("seasonal_crops.greenhouse.min_glass_count", 12);
        greenhouseRequireCore = cfg.getBoolean("seasonal_crops.greenhouse.require_core", false);

        String coreId = cfg.getString("seasonal_crops.greenhouse.core_block", "CARTOGRAPHY_TABLE");
        try {
            greenhouseCoreBlock = Material.valueOf(coreId.toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            greenhouseCoreBlock = null;
        }

        // Bloques válidos de invernadero (aquí puedes poner GLASS_PANE, etc.)
        greenhouseBlocks = new HashSet<>();
        for (String id : cfg.getStringList("seasonal_crops.greenhouse.block_types")) {
            try {
                greenhouseBlocks.add(Material.valueOf(id.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }

        // Debug stick
        debugEnabled = cfg.getBoolean("seasonal_crops.greenhouse.debug.enabled", true);
        String stickId = cfg.getString("seasonal_crops.greenhouse.debug.stick", "STICK");
        try {
            debugStickMaterial = Material.valueOf(stickId.toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            debugStickMaterial = Material.STICK;
        }

        // Estaciones por cultivo
        cropSeasons.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("seasonal_crops.crops");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    Material mat = Material.valueOf(key.toUpperCase(Locale.ROOT));
                    List<String> allowed = sec.getStringList(key + ".allowed_seasons");
                    EnumSet<Season> set = EnumSet.noneOf(Season.class);
                    for (String s : allowed) {
                        try {
                            set.add(Season.valueOf(s.toUpperCase(Locale.ROOT)));
                        } catch (Exception ignored) {}
                    }
                    cropSeasons.put(mat, set);
                } catch (Exception ex) {
                    plugin.getLogger().warning("[Crops] Tipo de cultivo no reconocido: " + key);
                }
            }
        }
    }

    // Getters

    public boolean isEnabled() {
        return enabled;
    }

    public double getRainBonus() {
        return rainBonus;
    }

    public double getWinterGreenhouseBonus() {
        return winterGreenhouseBonus;
    }

    public double getUndergroundMultiplier() {
        return undergroundMultiplier;
    }

    public double getLowLightMultiplier() {
        return lowLightMultiplier;
    }

    public int getRequiredLight() {
        return requiredLight;
    }

    public boolean isGreenhouseEnabled() {
        return greenhouseEnabled;
    }

    public Set<Material> getGreenhouseBlocks() {
        return greenhouseBlocks;
    }

    public int getMaxRoofHeight() {
        return maxRoofHeight;
    }

    public int getGreenhouseRadius() {
        return greenhouseRadius;
    }

    public int getGreenhouseMinGlass() {
        return greenhouseMinGlass;
    }

    public boolean isGreenhouseRequireCore() {
        return greenhouseRequireCore;
    }

    public Material getGreenhouseCoreBlock() {
        return greenhouseCoreBlock;
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public Material getDebugStickMaterial() {
        return debugStickMaterial;
    }

    public EnumSet<Season> getAllowedSeasons(Material mat) {
        return cropSeasons.get(mat);
    }

    public boolean isManagedCrop(Material mat) {
        return cropSeasons.containsKey(mat);
    }
}
