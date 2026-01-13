package Kinkin.aeternum.hud;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import com.tcoded.folialib.wrapper.task.WrappedTask;

import java.util.*;

public final class HudService implements Listener, Runnable {

    public enum HudMode {
        FIXED,
        VARIABLE,
        OFF
    }

    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;

    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, HudMode> modes = new HashMap<>();

    // Persistentes por jugador
    private final Set<UUID> variablePlayers = new HashSet<>();
    private final Set<UUID> offPlayers = new HashSet<>();

    private WrappedTask task;

    // Antes era "enabled" (bossbar). Ahora separo para poder activar ActionBar sin romper nada.
    private final boolean bossbarEnabled;
    private final boolean actionbarEnabled;

    private final boolean colorBySeason;
    private final long updateTicks;
    private final HudMode defaultMode;

    public HudService(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        this.plugin = plugin;
        this.seasons = seasons;

        this.bossbarEnabled   = plugin.cfg.hud.getBoolean("bossbar.enabled", true);
        this.actionbarEnabled = plugin.cfg.hud.getBoolean("actionbar.enabled", false);

        this.colorBySeason = plugin.cfg.hud.getBoolean("bossbar.color_by_season", true);
        this.updateTicks   = plugin.cfg.hud.getLong("bossbar.update_ticks", 40L);

        String rawDefault = plugin.cfg.hud.getString("bossbar.default_mode", "FIXED");
        HudMode dm;
        try {
            dm = HudMode.valueOf(rawDefault.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            dm = HudMode.FIXED;
        }
        this.defaultMode = dm;

        // cargar variable_players
        for (String s : plugin.cfg.hud.getStringList("bossbar.variable_players")) {
            try { variablePlayers.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }

        // cargar off_players
        for (String s : plugin.cfg.hud.getStringList("bossbar.off_players")) {
            try { offPlayers.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
    }

    public void register() {
        // Si no hay bossbar ni actionbar, no hacemos nada.
        if (!bossbarEnabled && !actionbarEnabled) return;

        Bukkit.getPluginManager().registerEvents(this, plugin);

        if (bossbarEnabled) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                ensureBar(p);
            }
        }

        this.task = plugin.getScheduler().runTimer(this, 1L, updateTicks);
    }

    public void unregister() {
        if (task != null) task.cancel();
        HandlerList.unregisterAll(this);

        bars.values().forEach(BossBar::removeAll);
        bars.clear();
        modes.clear();
        variablePlayers.clear();
        offPlayers.clear();
    }

    // ───────────────────── Eventos de jugador ─────────────────────

    @org.bukkit.event.EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (bossbarEnabled) ensureBar(e.getPlayer());
    }

    @org.bukkit.event.EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!bossbarEnabled) return;
        plugin.getScheduler().runNextTick(task -> ensureBar(e.getPlayer()));
    }

    @org.bukkit.event.EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (bossbarEnabled) ensureBar(e.getPlayer());
    }

    @org.bukkit.event.EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        BossBar bar = bars.remove(id);
        if (bar != null) bar.removeAll();

        // limpiar actionbar al salir (opcional pero queda limpio)
        if (actionbarEnabled) {
            e.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
        }

        modes.remove(id);
        // variablePlayers/offPlayers NO se limpian: son persistentes
    }

    private void ensureBar(Player p) {
        UUID id = p.getUniqueId();
        BossBar bar = bars.get(id);

        if (bar == null) {
            bar = Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SEGMENTED_10);
            bars.put(id, bar);
        }

        if (!bar.getPlayers().contains(p)) {
            bar.addPlayer(p);
        }
    }

    /** Cambia modo del jugador y lo guarda persistente. */
    public void setPlayerMode(Player p, HudMode mode) {
        UUID id = p.getUniqueId();
        modes.put(id, mode);

        // OFF tiene prioridad: si está OFF, no debe estar marcado variable
        if (mode == HudMode.OFF) {
            offPlayers.add(id);
            variablePlayers.remove(id);
        } else if (mode == HudMode.VARIABLE) {
            variablePlayers.add(id);
            offPlayers.remove(id);
        } else { // FIXED
            variablePlayers.remove(id);
            offPlayers.remove(id);
        }

        if (bossbarEnabled) {
            ensureBar(p); // si volvió de OFF, lo re-añade
        }

        savePlayers("bossbar.variable_players", variablePlayers);
        savePlayers("bossbar.off_players", offPlayers);
    }

    /** Modo del jugador, leyendo persistencia. */
    public HudMode getPlayerMode(Player p) {
        UUID id = p.getUniqueId();

        HudMode mem = modes.get(id);
        if (mem != null) return mem;

        if (offPlayers.contains(id)) {
            modes.put(id, HudMode.OFF);
            return HudMode.OFF;
        }
        if (variablePlayers.contains(id)) {
            modes.put(id, HudMode.VARIABLE);
            return HudMode.VARIABLE;
        }

        return defaultMode;
    }

    private void savePlayers(String path, Set<UUID> set) {
        List<String> out = new ArrayList<>();
        for (UUID u : set) out.add(u.toString());
        plugin.cfg.hud.set(path, out);
        plugin.saveConfig(); // si tienes saveHudConfig(), cámbialo aquí
    }

    @Override
    public void run() {
        CalendarState s = seasons.getStateCopy();
        int daysPerSeason = seasons.getDaysPerSeason();

        World overworld = primaryOverworld();
        long time = (overworld != null ? overworld.getTime() : 0L);

        for (Player p : Bukkit.getOnlinePlayers()) {

            // 1. DECLARACIÓN y VERIFICACIÓN (MANTENER ESTE BLOQUE)
            World pw = p.getWorld(); // Mantenemos esta declaración
            if (plugin.isWorldDisabled(pw)) {
                // Si el plugin está deshabilitado en este mundo, ocultamos y removemos la Bossbar.
                BossBar bar = bars.remove(p.getUniqueId());
                if (bar != null) bar.removeAll();

                // limpiar actionbar
                if (actionbarEnabled) {
                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
                }

                HudMode currentMode = getPlayerMode(p);
                if (currentMode != HudMode.OFF) {
                    modes.remove(p.getUniqueId());
                }

                continue; // Salta al siguiente jugador
            }

            BossBar bar = null;
            if (bossbarEnabled) {
                ensureBar(p);
                bar = bars.get(p.getUniqueId());
                if (bar == null) continue;
            }

            HudMode mode = getPlayerMode(p);
            if (mode == HudMode.OFF) {
                if (bossbarEnabled) {
                    bar.setVisible(false);
                    bar.removePlayer(p);
                }
                if (actionbarEnabled) {
                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
                }
                continue;
            } else {
                if (bossbarEnabled) {
                    if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
                }
            }

            // 2. LÓGICA DE DIMENSIONES (¡QUITAR LA REDECLARACIÓN AQUÍ!)
            // [Línea anterior era 'World pw = p.getWorld();' - DEBE SER ELIMINADA]
            boolean inFrostOverworld = pw != null && pw.getName().equalsIgnoreCase("aeternum_frost");
            boolean inHeatWorld      = pw != null && pw.getName().equalsIgnoreCase("aeternum_heat");

            String title;
            Season visualSeason;
            double progress;

            if (inHeatWorld) {
                String realmName = plugin.lang.tr(p, "realm.heat_overworld");
                title = realmName;
                visualSeason = Season.SUMMER;
                progress = 1.0;

            } else if (inFrostOverworld) {
                // Día real del calendario, sin forzar al rango 1..days_per_season
                int frostDay = s.day;

                String seasonName = plugin.lang.tr(p, "season.WINTER");
                String realmName  = plugin.lang.tr(p, "realm.frost_overworld");
                visualSeason = Season.WINTER;

                title = plugin.lang.trf(p, "hud.title_dim", Map.of(
                        "day", frostDay,
                        "year", s.year,
                        "season", seasonName,
                        "realm", realmName
                ));

                // La barra se llena al llegar al final de la estación y luego se queda llena.
                progress = Math.max(0.0, Math.min(1.0,
                        (double) frostDay / (double) daysPerSeason
                ));

            } else {
                String seasonName = plugin.lang.tr(p, "season." + s.season.name());
                visualSeason = s.season;

                title = plugin.lang.trf(p, "hud.title", Map.of(
                        "day", s.day,
                        "year", s.year,
                        "season", seasonName
                ));

                progress = Math.max(0.0, Math.min(1.0,
                        (double) s.day / (double) daysPerSeason
                ));
            }

            // ───────────── ActionBar (mismo texto que la bossbar) ─────────────
            if (actionbarEnabled) {
                boolean showNow = (mode == HudMode.FIXED) || isHudTime(time);
                if (showNow) {
                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(title));
                } else {
                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
                }
            }

            // ───────────── BossBar original (sin cambiar tu lógica) ─────────────
            if (bossbarEnabled) {
                bar.setTitle(title);
                bar.setProgress(progress);

                if (colorBySeason) {
                    bar.setColor(switch (visualSeason) {
                        case SPRING -> BarColor.GREEN;
                        case SUMMER -> BarColor.YELLOW;
                        case AUTUMN -> BarColor.RED;
                        case WINTER -> BarColor.BLUE;
                    });
                }

                if (mode == HudMode.FIXED) {
                    bar.setVisible(true);
                } else { // VARIABLE
                    bar.setVisible(isHudTime(time));
                }
            }
        }
    }

    private World primaryOverworld() {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) return null;
        for (World w : worlds) {
            if (w.getEnvironment() == World.Environment.NORMAL) return w;
        }
        return worlds.get(0);
    }

    private boolean isHudTime(long time) {
        long t = time % 24000L;
        if (t >= 0 && t < 2000) return true;         // mañana
        if (t >= 6000 && t < 8000) return true;      // mediodía
        if (t >= 13000 && t < 15000) return true;    // noche
        return false;
    }
}
