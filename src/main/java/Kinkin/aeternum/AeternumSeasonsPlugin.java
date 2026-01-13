package Kinkin.aeternum;

import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.command.CmdSeasonGuide;
import Kinkin.aeternum.farming.SeasonalCropGrowthListener;
import Kinkin.aeternum.fauna.AnimalMigrationService;
import Kinkin.aeternum.hud.HudService;
import Kinkin.aeternum.items.SeasonClockService;
import Kinkin.aeternum.lang.LanguageManager;
import Kinkin.aeternum.command.SeasonCommand;
import Kinkin.aeternum.util.Configs;
import Kinkin.aeternum.weather.SeasonalWeatherService;
import Kinkin.aeternum.world.*;
import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import org.bukkit.*;
import org.bukkit.command.PluginCommand;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import net.md_5.bungee.api.ChatColor;
import java.util.Map;

public final class AeternumSeasonsPlugin extends JavaPlugin {

    public Configs cfg;
    private SeasonService seasons;
    private HudService hud;
    public LanguageManager lang;
    private WinterWorldPainter winterPainter;
    private BiomeSpoofAdapter biomeSpoof;
    private SeasonalWeatherService seasonalWeather;
    private SeasonalCropGrowthListener cropGrowth;
    private AutumnSoilPainter autumnSoilPainter;
    private CanopySnowPainter canopySnowPainter;
    public AnimalMigrationService migration;
    private SeasonalFloraController flora;
    private SeasonClockService seasonClock;
    private FastLeafDecayService fastLeafDecay;
    private VillagerTypeOverrides villagerTypes;
    private java.util.List<String> disabledWorlds = new java.util.ArrayList<>();
    private BiomeSpoofSpawnGuard biomeSpoofSpawnGuard;
    private FoliaLib foliaLib;

    private void loadWorldExclusionList() {
        // Obtenemos la lista de la nueva sección 'worlds.disabled_season_fx'
        this.disabledWorlds = getConfig().getStringList("worlds.disabled_season_fx");
        if (this.disabledWorlds == null) {
            this.disabledWorlds = java.util.Collections.emptyList();
        }
        getLogger().info("[SeasonsCore] FX deshabilitados en " + this.disabledWorlds.size() + " mundos: " + this.disabledWorlds);
    }

    // Helper público para que otras clases puedan verificar un mundo
    // Devuelve TRUE si el mundo está en la lista (DEBE SER DESHABILITADO)
    public boolean isWorldDisabled(World world) {
        if (world == null) return true;
        return disabledWorlds.contains(world.getName());
    }


    @Override public void onEnable() {
        saveDefaultConfig();
        this.foliaLib = new FoliaLib(this);
        this.cfg = new Configs(this);
        this.lang = new LanguageManager(this);
        lang.register();
        cfg.loadAll();
        loadWorldExclusionList();
        WinterWorldGuardHelper.init(this);

        // === flags de config ===
        boolean frostEnabled = getConfig().getBoolean("features.portals.frost.enabled", true);
        boolean heatEnabled  = getConfig().getBoolean("features.portals.heat.enabled", true);

        this.seasons = new SeasonService(this);
        this.hud     = new HudService(this, seasons);
        this.winterPainter = new WinterWorldPainter(this, seasons);
        this.biomeSpoof = new BiomeSpoofAdapter(this, seasons);
        this.biomeSpoofSpawnGuard = new BiomeSpoofSpawnGuard(this, biomeSpoof);
        this.biomeSpoofSpawnGuard.setEnabled(cfg.climate.getBoolean("biome_spoof.spawn_guard.enabled", true));
        this.seasonalWeather = new SeasonalWeatherService(this, seasons);
        this.cropGrowth = new SeasonalCropGrowthListener(this, seasons);
        this.autumnSoilPainter = new AutumnSoilPainter(this, seasons);
        this.migration = new AnimalMigrationService(this, seasons);
        this.canopySnowPainter = new CanopySnowPainter(this, seasons);
        this.flora = new SeasonalFloraController(this, seasons);
        this.fastLeafDecay = new FastLeafDecayService(this);
        this.villagerTypes = new VillagerTypeOverrides(this, this.lang);
        getServer().getPluginManager().registerEvents(villagerTypes, this);

        //CRAFTEOS
        this.seasonClock = new SeasonClockService(this, seasons);

        SeasonCommand cmd = new SeasonCommand(this, seasons, hud, biomeSpoof);
        PluginCommand seasonCmd = getCommand("season");
        if (seasonCmd != null) {
            seasonCmd.setExecutor(cmd);
            seasonCmd.setTabCompleter(cmd);
        }

        CmdSeasonGuide guideCmd = new CmdSeasonGuide(this, lang, seasons);

        if (getCommand("seasonguide") != null) {
            getCommand("seasonguide").setExecutor(guideCmd);
            getCommand("seasonguide").setTabCompleter(guideCmd);
        }
        seasons.register();
        hud.register();
        winterPainter.register();
        biomeSpoof.register();
        migration.register();
        autumnSoilPainter.register();
        cropGrowth.register();
        seasonalWeather.register();
        canopySnowPainter.register();
        flora.register();
        fastLeafDecay.register();
        biomeSpoofSpawnGuard.register();

        //CRAFTEOS
        seasonClock.register();

        // === Soporte opcional de PlaceholderAPI ===
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new AeternumPlaceholders(this).register();
            getLogger().info("[SeasonsCore] PlaceholderAPI detected, placeholders registered.");
        } else {
            getLogger().info("[SeasonsCore] PlaceholderAPI not found, skipping placeholder registration.");
        }

        getLogger().info("SeasonsCore enabled.");
    }

    @Override public void onDisable() {
        if (hud != null) hud.unregister();
        if (seasons != null) seasons.persistNow();
        if (winterPainter != null) winterPainter.unregister();
        if (biomeSpoof != null) biomeSpoof.unregister();
        if (biomeSpoofSpawnGuard != null) biomeSpoofSpawnGuard.unregister();
        if (seasonalWeather != null) seasonalWeather.unregister();
        if (cropGrowth != null) cropGrowth.unregister();
        if (autumnSoilPainter  != null) autumnSoilPainter.unregister();
        if (migration != null) migration.unregister();
        if (flora != null) flora.unregister();

        if (canopySnowPainter != null) {
            canopySnowPainter.unregister();
        }

        if (seasonClock != null) {
            seasonClock.unregister();
        }
        if (fastLeafDecay != null) {
            fastLeafDecay.unregister();
        }
        if (villagerTypes != null) {
            org.bukkit.event.HandlerList.unregisterAll(villagerTypes);
        }
        if (foliaLib != null) {
            foliaLib.getScheduler().cancelAllTasks();
        }

    }

    /* ===================== RELOAD ===================== */

    public synchronized void reloadEverything() {
        getLogger().info("[SeasonsCore] Reload start...");

        // 1) Persistir y desmontar
        if (hud != null) hud.unregister();
        if (winterPainter != null) winterPainter.unregister();
        if (biomeSpoof != null) biomeSpoof.unregister();
        if (biomeSpoofSpawnGuard != null) biomeSpoofSpawnGuard.unregister();
        if (seasons != null) seasons.persistNow();
        if (seasonalWeather != null) seasonalWeather.unregister();
        if (cropGrowth != null) cropGrowth.unregister();
        if (autumnSoilPainter  != null) autumnSoilPainter.unregister();
        if (migration != null) migration.unregister();
        if (flora != null) flora.unregister();
        if (canopySnowPainter != null) canopySnowPainter.unregister();
        if (seasonClock != null) seasonClock.unregister();
        if (fastLeafDecay != null) {
            fastLeafDecay.unregister();
        }
        if (villagerTypes != null) {
            org.bukkit.event.HandlerList.unregisterAll(villagerTypes);
        }

        // 2) Recargar configs e idiomas


        // flags frescos (si los usas en otro lado)
        boolean frostEnabled = getConfig().getBoolean("features.portals.frost.enabled", true);
        boolean heatEnabled  = getConfig().getBoolean("features.portals.heat.enabled", true);

        // Reinstanciar LanguageManager
        this.lang = new LanguageManager(this);
        lang.register();

        // 3) Reinstanciar servicios con la config nueva
        this.seasons = new SeasonService(this);
        this.hud     = new HudService(this, seasons);
        this.winterPainter = new WinterWorldPainter(this, seasons);
        this.biomeSpoof = new BiomeSpoofAdapter(this, seasons);
        this.biomeSpoofSpawnGuard = new BiomeSpoofSpawnGuard(this, biomeSpoof);
        this.biomeSpoofSpawnGuard.setEnabled(cfg.climate.getBoolean("biome_spoof.spawn_guard.enabled", true));
        this.seasonalWeather = new SeasonalWeatherService(this, seasons);
        this.cropGrowth = new SeasonalCropGrowthListener(this, seasons);
        this.autumnSoilPainter = new AutumnSoilPainter(this, seasons);
        this.migration = new AnimalMigrationService(this, seasons);
        this.canopySnowPainter = new CanopySnowPainter(this, seasons);
        this.flora = new SeasonalFloraController(this, seasons);
        this.seasonClock = new SeasonClockService(this, seasons);
        this.fastLeafDecay = new FastLeafDecayService(this);

        // 4) Registrar todo de nuevo
        seasons.register();
        hud.register();
        winterPainter.register();
        biomeSpoof.register();
        migration.register();
        autumnSoilPainter.register();
        cropGrowth.register();
        seasonalWeather.register();
        canopySnowPainter.register();
        flora.register();
        fastLeafDecay.register();
        biomeSpoofSpawnGuard.register();

        // CRAFTEOS: limpiar receta vieja y registrar de nuevo
        if (seasonClock != null) {
            Bukkit.removeRecipe(new NamespacedKey(this, "season_clock"));
            seasonClock.register();
        }

        Bukkit.getLogger().info("[SeasonsCore] Reload done.");
    }

    public SeasonService getSeasons() {
        return seasons;
    }

    public FoliaLib getFoliaLib() {
        return foliaLib;
    }

    public PlatformScheduler getScheduler() {
        return foliaLib.getScheduler();
    }

}
