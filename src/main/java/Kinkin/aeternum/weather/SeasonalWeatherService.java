package Kinkin.aeternum.weather;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.calendar.SeasonUpdateEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class SeasonalWeatherService implements Listener {

    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;

    private boolean enabled;
    private List<String> worldIds;

    // Valor global por defecto
    private int rainyDaysPerSeason;



    // Overrides por estación
    private final EnumMap<Season, Integer> rainyDaysOverrides = new EnumMap<>(Season.class);

    private double thunderChance;
    private int stormMin, stormMax;
    private int clearMin, clearMax;
    private boolean reseedEachSeason;
    private boolean respectManual;

    // estado actual
    private static final int BASE_SEASON_LENGTH = 28;
    private final Set<Integer> rainyDays = new HashSet<>(); // días lluviosos 1..daysPerSeason
    private long lastAppliedWorldDay = Long.MIN_VALUE;      // idx de día del mundo para no reaplicar en bucle
    private boolean manualOverrideToday = false;

    public SeasonalWeatherService(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        this.plugin = plugin;
        this.seasons = seasons;
        reloadFromConfig();
        loadOrSeedSchedule(seasons.getStateCopy());
    }

    public void register() {
        if (!enabled) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // aplicar clima del día actual al registrar
        applyForTodayGlobal();
        // reloj suave: chequea índice de día del mundo para re-aplicar al amanecer si hace falta
        plugin.getScheduler().runTimer(this::tickWorldClock, 60L, 40L);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    private void reloadFromConfig() {
        enabled = plugin.cfg.climate.getBoolean("seasonal_weather.enabled", true);

        worldIds = new ArrayList<>(plugin.cfg.climate.getStringList("seasonal_weather.worlds"));
        if (worldIds.isEmpty()) {
            worldIds = List.of("world");
        }

        // Global fallback
        rainyDaysPerSeason = Math.max(0,
                plugin.cfg.climate.getInt("seasonal_weather.rainy_days_per_season", 10)
        );

        // Overrides por estación: seasonal_weather.rainy_days.SPRING / SUMMER / AUTUMN / WINTER
        rainyDaysOverrides.clear();
        ConfigurationSection sec = plugin.cfg.climate.getConfigurationSection("seasonal_weather.rainy_days");
        if (sec != null) {
            for (Season s : Season.values()) {
                // si no está en config, usa el global
                int v = Math.max(0, sec.getInt(s.name(), rainyDaysPerSeason));
                rainyDaysOverrides.put(s, v);
            }
        } else {
            // si no hay sección, todas las estaciones usan el global
            for (Season s : Season.values()) {
                rainyDaysOverrides.put(s, rainyDaysPerSeason);
            }
        }

        thunderChance = Math.max(0.0, Math.min(1.0,
                plugin.cfg.climate.getDouble("seasonal_weather.thunder_chance", 0.20)
        ));

        stormMin = plugin.cfg.climate.getInt("seasonal_weather.storm_duration_ticks.min", 6000);
        stormMax = plugin.cfg.climate.getInt("seasonal_weather.storm_duration_ticks.max", 18000);

        clearMin = plugin.cfg.climate.getInt("seasonal_weather.clear_duration_ticks.min", 6000);
        clearMax = plugin.cfg.climate.getInt("seasonal_weather.clear_duration_ticks.max", 24000);

        reseedEachSeason = plugin.cfg.climate.getBoolean("seasonal_weather.reseed_each_season", true);
        respectManual = plugin.cfg.climate.getBoolean("seasonal_weather.respect_manual_commands", true);
    }

    @EventHandler
    public void onSeasonEvent(SeasonUpdateEvent e) {
        if (!enabled) return;
        CalendarState st = e.getState();

        // al iniciar estación (day=1) o si reseed activo, resiembra patrón
        if (st.day == 1 && (reseedEachSeason || rainyDays.isEmpty())) {
            seedSchedule(st);
            saveSchedule(st);
        }

        // aplicar clima del día actual según agenda
        applyForTodayGlobal();

        // reset del override manual al cambiar de día/estación
        manualOverrideToday = false;
    }

    /* ===== núcleo ===== */

    private void tickWorldClock() {
        if (!enabled) return;

        // detecta avance de índice de día del mundo para re-aplicar al amanecer
        long idx = primaryDayIndex();
        if (idx == Long.MIN_VALUE) return;

        if (idx != lastAppliedWorldDay) {
            lastAppliedWorldDay = idx;
            // amaneció un nuevo día: volvemos a aplicar según agenda (a menos que respetemos manual)
            manualOverrideToday = false;
            applyForToday();
        }
    }

    private void applyForTodayGlobal() {
        plugin.getScheduler().runNextTick(task -> applyForToday());
    }

    private void applyForToday() {
        if (!enabled) return;
        CalendarState st = seasons.getStateCopy();

        // si respetamos cambios manuales y alguien ya puso /weather clear/rain hoy, no tocamos
        if (respectManual && manualOverrideToday) return;

        boolean rainyToday = rainyDays.contains(st.day);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        boolean thunder = rainyToday && (r.nextDouble() < thunderChance);

        int stormTicks = clampRand(stormMin, stormMax);
        int clearTicks = clampRand(clearMin, clearMax);

        for (World w : worldsToApply()) {
            if (rainyToday) {
                w.setStorm(true);
                w.setWeatherDuration(stormTicks);
                w.setThunderDuration(thunder ? stormTicks : 0);
                w.setThundering(thunder);
            } else {
                w.setStorm(false);
                w.setWeatherDuration(clearTicks);
                w.setThunderDuration(0);
                w.setThundering(false);
            }
        }
    }

    private List<World> worldsToApply() {
        List<World> out = new ArrayList<>();
        for (String id : worldIds) {
            World w = Bukkit.getWorld(id);
            if (w != null) out.add(w);
        }
        return out;
    }

    /* ===== si el admin usa /weather, marcar override de hoy ===== */

    public void markManualOverride() {
        // La puedes llamar desde tu listener de comandos de /weather
        this.manualOverrideToday = true;
    }

    /* ===== schedule ===== */

    private void loadOrSeedSchedule(CalendarState st) {
        if (!loadSchedule(st)) {
            seedSchedule(st);
            saveSchedule(st);
        }
    }

    private void seedSchedule(CalendarState st) {
        rainyDays.clear();

        int days = seasons.getDaysPerSeason();
        if (days <= 0) return;

        // Valor base desde config (pensado para una estación de 28 días)
        int baseTarget = rainyDaysOverrides.getOrDefault(
                st.season,
                rainyDaysPerSeason
        );

        // Escala al tamaño real de la estación
        int target;
        if (days == BASE_SEASON_LENGTH) {
            target = baseTarget;
        } else {
            double factor = days / (double) BASE_SEASON_LENGTH;
            target = (int) Math.round(baseTarget * factor);
        }

        // No pasar del número de días reales
        int need = Math.min(Math.max(0, target), days);

        // semilla estable por (Año+Estación) para que la agenda sea reproducible
        long seed = (st.year * 1315423911L) ^ (st.season.ordinal() * 2654435761L);
        Random rnd = new Random(seed);

        while (rainyDays.size() < need) {
            int d = 1 + rnd.nextInt(days); // 1..days
            rainyDays.add(d);
        }
    }


    private boolean loadSchedule(CalendarState st) {
        try {
            File f = scheduleFile(st);
            if (!f.exists()) return false;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            List<Integer> list = y.getIntegerList("rainy_days");
            rainyDays.clear();
            rainyDays.addAll(list);
            return !rainyDays.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private void saveSchedule(CalendarState st) {
        try {
            File f = scheduleFile(st);
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            YamlConfiguration y = new YamlConfiguration();
            y.set("year", st.year);
            y.set("season", st.season.name());
            y.set("rainy_days", new ArrayList<>(rainyDays));
            y.save(f);
        } catch (IOException ignored) {
        }
    }

    private File scheduleFile(CalendarState st) {
        return new File(plugin.getDataFolder(), "data/weather_" + st.year + "_" + st.season.name() + ".yml");
    }

    /* ===== util ===== */

    private int clampRand(int min, int max) {
        if (max < min) {
            int t = min;
            min = max;
            max = t;
        }
        if (min < 1) min = 1;
        if (max < 1) max = min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private long primaryDayIndex() {
        World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (w == null) return Long.MIN_VALUE;

        if (w.getEnvironment() != World.Environment.NORMAL) {
            for (World ww : Bukkit.getWorlds()) {
                if (ww.getEnvironment() == World.Environment.NORMAL) {
                    w = ww;
                    break;
                }
            }
        }

        if (w == null) return Long.MIN_VALUE;
        // 1 día Minecraft = 24000 ticks
        return w.getFullTime() / 24000L;
    }
}
