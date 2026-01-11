package Kinkin.aeternum.calendar;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.List;

public final class SeasonService implements Listener, Runnable {
    private final AeternumSeasonsPlugin plugin;

    // ❗ ya no es final
    private int daysPerSeason = 28;

    private final boolean advanceOnSleep;          // sólo si no seguimos reloj
    private final int  realTimeMinutesPerDay;      // modo “tiempo real”
    private final boolean followOverworldTime;     // seguir reloj MC
    private final boolean requirePlayersOnServer;

    private CalendarState state;

    // tareas
    private BukkitTask rtTask;       // tiempo real (si se usa)
    private BukkitTask worldClock;   // seguidor de reloj overworld

    // seguimiento de día del mundo (fullTime/24000)
    private long lastWorldDayIdx = Long.MIN_VALUE;

    // ANTI DOBLE AVANCE (sleep + reloj, etc.)
    private long lastDayAdvanceMs = 0L;

    public SeasonService(AeternumSeasonsPlugin plugin) {
        this.plugin = plugin;

        // leemos primero los flags (con rutas compatibles)
        this.advanceOnSleep        = readBool("advance.on_sleep", "calendar.advance.on_sleep", true);
        this.realTimeMinutesPerDay = readInt("advance.real_time_minutes_per_day", "calendar.advance.real_time_minutes_per_day", 0);
        this.followOverworldTime   = readBool("advance.follow_overworld_time", "calendar.advance.follow_overworld_time", true);

        // 🔹 NUEVO: require_players_on_server
        this.requirePlayersOnServer = readBool(
                "advance.require_players_on_server",
                "calendar.advance.require_players_on_server",
                true
        );

        this.state = loadState();
        reloadCalendarSettings(); // <- carga days_per_season correcto
    }

    /**
     * Intenta leer primero sin prefijo; si no existe, usa "calendar.<path>".
     */
    private int readInt(String plainPath, String calendarPath, int def) {
        int v = plugin.cfg.calendar.getInt(plainPath, Integer.MIN_VALUE);
        if (v == Integer.MIN_VALUE) {
            v = plugin.cfg.calendar.getInt(calendarPath, def);
        }
        return v;
    }

    private boolean readBool(String plainPath, String calendarPath, boolean def) {
        if (plugin.cfg.calendar.contains(plainPath)) {
            return plugin.cfg.calendar.getBoolean(plainPath, def);
        }
        if (plugin.cfg.calendar.contains(calendarPath)) {
            return plugin.cfg.calendar.getBoolean(calendarPath, def);
        }
        return def;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Modo tiempo real (opcional)
        if (realTimeMinutesPerDay > 0) {
            if (rtTask != null) rtTask.cancel(); // Cancelar por seguridad
            long period = 20L * 60L * realTimeMinutesPerDay;
            this.rtTask = Bukkit.getScheduler().runTaskTimer(plugin, this, period, period);
        }

        // Seguir reloj del mundo (recomendado)
        if (followOverworldTime) {
            if (worldClock != null) worldClock.cancel(); // Cancelar por seguridad
            // chequeo suave cada 10 ticks
            this.worldClock = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWorldClock, 40L, 10L);
        }
    }

    public void unregister() {
        if (rtTask != null) rtTask.cancel();
        if (worldClock != null) worldClock.cancel();

        HandlerList.unregisterAll(this);
    }

    /**
     * Vuelve a leer calendar.days_per_season desde config.
     * Llamar después de recargar la config.
     */
    public void reloadCalendarSettings() {
        int v = readInt("days_per_season", "calendar.days_per_season", 28);
        int newValue = Math.max(4, v);

        if (newValue != this.daysPerSeason) {
            this.daysPerSeason = newValue;

            if (state != null && state.day > daysPerSeason) {
                state.day = daysPerSeason;
                persistNow();
            }
        }
    }

    public int getDaysPerSeason() {
        return daysPerSeason;
    }

    /** Usado por el modo tiempo real exclusivamente. */
    @Override
    public void run() {
        // Si el server exige jugadores y no hay nadie en el servidor, no avanzamos.
        if (requirePlayersOnServer && !hasAnyOnlinePlayer()) {
            return;
        }
        nextDay();
    }

    /* ==================== Núcleo ==================== */

    /** Detecta cambio de día en cualquiera de los mundos overworld. */
    private void tickWorldClock() {
        long maxIdx = Long.MIN_VALUE;

        // Miramos TODOS los mundos con Environment.NORMAL (overworld-like)
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != World.Environment.NORMAL) continue;

            long full = w.getFullTime();   // ticks totales de ese mundo
            long idx  = full / 24000L;     // índice de día para ese mundo

            if (idx > maxIdx) {
                maxIdx = idx;
            }
        }

        // Si no hay mundos normales, no hacemos nada
        if (maxIdx == Long.MIN_VALUE) return;

        // Si la config exige jugadores en el server y no hay nadie, solo sincronizamos índice
        if (requirePlayersOnServer && !hasAnyOnlinePlayer()) {
            lastWorldDayIdx = maxIdx;
            return;
        }

        // Inicializamos el índice almacenado
        if (lastWorldDayIdx == Long.MIN_VALUE) {
            lastWorldDayIdx = maxIdx;
            return;
        }

        // Si algún mundo pasó a un nuevo día, avanzamos el calendario UNA vez
        if (maxIdx != lastWorldDayIdx) {
            lastWorldDayIdx = maxIdx;
            nextDay();
        }
    }

    /** Mundo base: primer mundo NORMAL. Si no hay, usa el primero. */
    private World primaryOverworld() {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) return null;
        for (World w : worlds) if (w.getEnvironment() == World.Environment.NORMAL) return w;
        return worlds.get(0);
    }

    // 🔹 NUEVO: vale para cualquier dimensión
    private boolean hasAnyOnlinePlayer() {
        return !Bukkit.getOnlinePlayers().isEmpty();
    }

    /* ==================== API ==================== */

    public synchronized void nextDay() {
        // ANTI DOBLE AVANCE: si dos fuentes disparan seguido, sólo cuenta una
        long now = System.currentTimeMillis();
        if (now - lastDayAdvanceMs < 500L) {
            return;
        }
        lastDayAdvanceMs = now;

        state.day++;
        if (state.day > daysPerSeason) {
            state.day = 1;
            state.season = nextSeason(state.season);
            if (state.season == Season.SPRING) state.year++;
        }
        persistNow();
        Bukkit.getPluginManager().callEvent(new SeasonUpdateEvent(this, getStateCopy(), true));
    }

    public synchronized void setSeason(Season s) {
        state.season = s;
        if (state.day > daysPerSeason) state.day = daysPerSeason;
        persistNow();
        Bukkit.getPluginManager().callEvent(new SeasonUpdateEvent(this, getStateCopy(), false));
    }

    /** /season day <n> */
    public synchronized void setDay(int day) {
        if (day < 1) day = 1;
        if (day > daysPerSeason) day = daysPerSeason;
        state.day = day;
        persistNow();
        Bukkit.getPluginManager().callEvent(new SeasonUpdateEvent(this, getStateCopy(), false));
    }

    public synchronized void setYear(int year) {
        if (year < 1) year = 1; // mínimo año 1
        state.year = year;
        persistNow();
        Bukkit.getPluginManager().callEvent(new SeasonUpdateEvent(this, getStateCopy(), false));
    }

    public synchronized CalendarState getStateCopy() {
        return new CalendarState(state.year, state.day, state.season);
    }

    private CalendarState loadState() {
        File f = new File(plugin.getDataFolder(), "data/calendar.yml");
        if (!f.exists()) return new CalendarState(1, 1, Season.SPRING);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        int year = y.getInt("year", 1);
        int day = y.getInt("day", 1);
        Season s = Season.valueOf(y.getString("season", "SPRING"));
        return new CalendarState(year, day, s);
    }

    public void persistNow() {
        try {
            File f = new File(plugin.getDataFolder(), "data/calendar.yml");
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            YamlConfiguration y = new YamlConfiguration();
            y.set("year", state.year);
            y.set("day", state.day);
            y.set("season", state.season.name());
            y.save(f);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar calendar.yml: " + e.getMessage());
        }
    }

    private Season nextSeason(Season s) {
        return switch (s) {
            case SPRING -> Season.SUMMER;
            case SUMMER -> Season.AUTUMN;
            case AUTUMN -> Season.WINTER;
            case WINTER -> Season.SPRING;
        };
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerBedLeave(PlayerBedLeaveEvent e) {
        // Si no queremos avanzar por sueño, salimos
        if (!advanceOnSleep) return;

        final Player p = e.getPlayer();
        final World w = p.getWorld();

        // Opcional: solo mundos "tipo overworld"
        if (w.getEnvironment() != World.Environment.NORMAL) {
            return;
        }

        // Respeta la config de "solo si hay jugadores en el server"
        if (requirePlayersOnServer && !hasAnyOnlinePlayer()) {
            return;
        }

        // Se durmió -> cuenta como nuevo día
        nextDay();
    }
}
