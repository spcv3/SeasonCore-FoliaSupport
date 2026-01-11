package Kinkin.aeternum;

import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.lang.LanguageManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Locale;

public final class AeternumPlaceholders extends PlaceholderExpansion {

    private final AeternumSeasonsPlugin plugin;

    public AeternumPlaceholders(AeternumSeasonsPlugin plugin) {
        this.plugin = plugin;
    }

    // %aeternum_...%
    @Override
    public @NotNull String getIdentifier() {
        return "aeternum";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        // para que no se desregistre con /papi reload
        return true;
    }

    @Override
    public boolean canRegister() {
        // Solo registramos si PlaceholderAPI está realmente cargado
        return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null) return "";

        params = params.toLowerCase(Locale.ROOT);

        SeasonService seasons = plugin.getSeasons();
        LanguageManager lang  = plugin.lang;
        CalendarState state   = null;

        if (seasons != null) {
            try {
                state = seasons.getStateCopy();
            } catch (Throwable ignored) {
                // si algo raro pasa, devolvemos vacío
            }
        }

        World world = player.getWorld();

        switch (params) {

            /* ===================== LO QUE YA TE FUNCIONABA ===================== */

            // %aeternum_day% o %aeternum_seasons_day%
            case "day":
            case "seasons_day":
                if (state == null) return "";
                return String.valueOf(state.day);

            // %aeternum_year% o %aeternum_seasons_year%
            case "year":
            case "seasons_year":
                if (state == null) return "";
                return String.valueOf(state.year);

            // %aeternum_season% o %aeternum_seasons_season%
            case "season":
            case "seasons_season":
                if (state == null || lang == null) return "";
                Season season = state.season;
                if (season == null) return "";
                return lang.tr(player, "season." + season.name());

            // %aeternum_realm%, %aeternum_dimension%, %aeternum_seasons_realm%
            case "realm":
            case "dimension":
            case "seasons_realm":
                return resolveRealmName(player, world);

            // %aeternum_world_name%
            case "world_name":
                return world.getName();

            default:
                return null;
        }
    }

    private String resolveRealmName(Player p, World w) {
        String name = w.getName();

        if (plugin.lang == null) return name;

        if (name.equalsIgnoreCase("aeternum_frost")) {
            return plugin.lang.tr(p, "realm.frost");
        }
        if (name.equalsIgnoreCase("aeternum_heat")) {
            return plugin.lang.tr(p, "realm.heat");
        }

        String key = "realm.overworld";
        String val = plugin.lang.tr(p, key);

        if (val.equals(key)) {
            val = plugin.lang.tr(p, "realm.overworld_title");
        }
        return val;
    }

}
