package Kinkin.aeternum.command;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.hud.HudService;
import Kinkin.aeternum.world.BiomeSpoofAdapter;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public final class SeasonCommand implements CommandExecutor, TabCompleter {

    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private final HudService hud;
    private final BiomeSpoofAdapter biomeSpoof;

    public SeasonCommand(AeternumSeasonsPlugin plugin, SeasonService seasons,
                         HudService hud, BiomeSpoofAdapter biomeSpoof) {
        this.plugin = plugin;
        this.seasons = seasons;
        this.hud = hud;
        this.biomeSpoof = biomeSpoof;
    }

    // Helpers para traducción
    private String tr(CommandSender s, String key) {
        Player p = (s instanceof Player) ? (Player) s : null;
        return plugin.lang.tr(p, key);
    }

    private String trf(CommandSender s, String key, Map<String, Object> vars) {
        Player p = (s instanceof Player) ? (Player) s : null;
        return plugin.lang.trf(p, key, vars);
    }

    // Nombre de estación según lang (fallback al display actual si falta la key)
    private String seasonName(CommandSender s, Season season) {
        Player p = (s instanceof Player) ? (Player) s : null;

        String key = "season." + season.name();   // season.WINTER, season.SUMMER, etc
        String val = plugin.lang.tr(p, key);

        // fallback seguro por si no existe en el yml
        if (val == null || val.isBlank() || val.equalsIgnoreCase(key)) {
            return season.display();
        }
        return val;
    }


    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            if (!s.hasPermission("aeternum.command.info") && !s.hasPermission("aeternum.command.base"))
                return deny(s);

            CalendarState st = seasons.getStateCopy();
            Map<String, Object> vars = new HashMap<>();
            vars.put("season", seasonName(s, st.season));
            vars.put("day", st.day);
            vars.put("max", seasons.getDaysPerSeason());
            vars.put("year", st.year);

            s.sendMessage(trf(s, "cmd.season.info.line", vars));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set" -> {
                if (!s.hasPermission("aeternum.command.set")) return deny(s);
                if (args.length < 2) {
                    Map<String, Object> vars = Collections.singletonMap("label", label);
                    s.sendMessage(trf(s, "cmd.season.set.usage", vars));
                    return true;
                }
                Season target;
                try {
                    target = Season.valueOf(args[1].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    s.sendMessage(tr(s, "cmd.season.set.invalid"));
                    return true;
                }
                seasons.setSeason(target);

                Map<String, Object> vars = Collections.singletonMap("season", seasonName(s, target));
                s.sendMessage(trf(s, "cmd.season.set.success", vars));
                return true;
            }

            case "skipday" -> {
                if (!s.hasPermission("aeternum.command.skip")) return deny(s);
                seasons.nextDay();
                s.sendMessage(tr(s, "cmd.season.skipday.done"));
                return true;
            }

            case "day", "setday" -> {
                if (!s.hasPermission("aeternum.command.day")) return deny(s);
                if (args.length < 2) {
                    Map<String, Object> vars = new HashMap<>();
                    vars.put("label", label);
                    vars.put("max", seasons.getDaysPerSeason());
                    s.sendMessage(trf(s, "cmd.season.day.usage", vars));
                    return true;
                }
                int n;
                try {
                    n = Integer.parseInt(args[1]);
                } catch (NumberFormatException ex) {
                    s.sendMessage(tr(s, "cmd.season.day.invalid_number"));
                    return true;
                }
                seasons.setDay(n);

                Map<String, Object> vars = Collections.singletonMap("day", n);
                s.sendMessage(trf(s, "cmd.season.day.success", vars));
                return true;
            }
            case "year" -> {
                if (!s.hasPermission("aeternum.command.year") && !s.hasPermission("aeternum.command.base"))
                    return deny(s);

                if (args.length < 2) {
                    Map<String, Object> vars = Collections.singletonMap("label", label);
                    s.sendMessage(trf(s, "cmd.season.year.usage", vars));
                    return true;
                }

                int year;
                try {
                    year = Integer.parseInt(args[1]);
                } catch (NumberFormatException ex) {
                    s.sendMessage(tr(s, "cmd.season.year.invalid_number"));
                    return true;
                }

                seasons.setYear(year);

                Map<String, Object> vars = Collections.singletonMap("year", year);
                s.sendMessage(trf(s, "cmd.season.year.success", vars));
                return true;
            }

            case "reload" -> {
                if (!s.hasPermission("aeternum.command.reload")) return deny(s);
                plugin.cfg.loadAll(); // recarga calendar.yml, hud.yml, climate.yml, survival.yml
                s.sendMessage(tr(s, "cmd.season.reload.done"));
                return true;
            }

            // ─────────────────────────────────────────────────────────────
            // /season hud fixed|variable
            // ─────────────────────────────────────────────────────────────
            case "hud" -> {
                if (!s.hasPermission("aeternum.command.base")
                        && !s.hasPermission("aeternum.command.hud")) {
                    return deny(s);
                }

                if (!(s instanceof Player p)) {
                    s.sendMessage(tr(s, "cmd.season.hud.player_only"));
                    return true;
                }

                if (args.length < 2) {
                    HudService.HudMode current = hud.getPlayerMode(p);
                    String modeKey = "cmd.season.hud.mode." + current.name().toLowerCase(Locale.ROOT);
                    String modeName = plugin.lang.tr(p, modeKey);

                    Map<String, Object> varsUsage = Collections.singletonMap("label", label);
                    p.sendMessage(plugin.lang.trf(p, "cmd.season.hud.usage", varsUsage));

                    Map<String, Object> varsCurrent = Collections.singletonMap("mode", modeName);
                    p.sendMessage(plugin.lang.trf(p, "cmd.season.hud.current", varsCurrent));
                    return true;
                }

                String modeArg = args[1].toLowerCase(Locale.ROOT);
                HudService.HudMode mode;
                switch (modeArg) {
                    case "fixed", "fijo" -> mode = HudService.HudMode.FIXED;
                    case "variable", "var" -> mode = HudService.HudMode.VARIABLE;
                    case "off", "apagado", "disable", "disabled" -> mode = HudService.HudMode.OFF;
                    default -> {
                        p.sendMessage(tr(p, "cmd.season.hud.invalid"));
                        return true;
                    }
                }

                hud.setPlayerMode(p, mode);

                switch (mode) {
                    case FIXED -> p.sendMessage(tr(p, "cmd.season.hud.set.fixed"));
                    case VARIABLE -> p.sendMessage(tr(p, "cmd.season.hud.set.variable"));
                    case OFF -> p.sendMessage(tr(p, "cmd.season.hud.set.off"));
                }
                return true;

            }

            case "biomes" -> {
                if (!s.hasPermission("aeternum.command.biomes") && !s.hasPermission("aeternum.command.base"))
                    return deny(s);

                if (args.length < 2) {
                    s.sendMessage("§eUso: /season biomes <on|off|restore>");
                    return true;
                }

                String a1 = args[1].toLowerCase(Locale.ROOT);
                switch (a1) {
                    case "off" -> {
                        biomeSpoof.setEnabled(false);
                        s.sendMessage("§a[Season] Biome painting OFF.");
                    }
                    case "on" -> {
                        biomeSpoof.setEnabled(true);
                        s.sendMessage("§a[Season] Biome painting ON.");
                    }
                    case "restore" -> {
                        biomeSpoof.setEnabled(false); // apaga pintado automático

                        int budget = Math.max(1,
                                plugin.cfg.climate.getInt("biome_spoof.restore_budget_chunks_per_tick", 6));

                        biomeSpoof.getDiskBackups().startRestoreAll(s, budget);
                        s.sendMessage("§a[Season] Restaurando biomas con budget " + budget + "/tick...");
                    }
                    default -> s.sendMessage("§eUso: /season biomes <on|off|restore>");
                }
                return true;
            }


            default -> {
                Map<String, Object> vars = Collections.singletonMap("label", label);
                s.sendMessage(trf(s, "cmd.season.help", vars));
                return true;
            }
        }
    }

    private boolean deny(CommandSender s) {
        s.sendMessage(tr(s, "cmd.season.no_permission"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] args) {

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);

            List<String> out = new ArrayList<>();

            // visibles si tiene permiso (o base para admins)
            if (s.hasPermission("aeternum.command.info") || s.hasPermission("aeternum.command.base"))
                out.add("info");

            if (s.hasPermission("aeternum.command.hud") || s.hasPermission("aeternum.command.base"))
                out.add("hud");

            if (s.hasPermission("aeternum.command.set") || s.hasPermission("aeternum.command.base"))
                out.add("set");

            if (s.hasPermission("aeternum.command.skip") || s.hasPermission("aeternum.command.base"))
                out.add("skipday");

            if (s.hasPermission("aeternum.command.day") || s.hasPermission("aeternum.command.base"))
                out.add("day");

            if (s.hasPermission("aeternum.command.reload") || s.hasPermission("aeternum.command.base"))
                out.add("reload");

            if (s.hasPermission("aeternum.command.biomes") || s.hasPermission("aeternum.command.base"))
                out.add("biomes");

            if (s.hasPermission("aeternum.command.year") || s.hasPermission("aeternum.command.base"))
                out.add("year");

            // filtra por lo que ya escribió
            out.removeIf(sub -> !sub.startsWith(prefix));
            return out;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            if (!s.hasPermission("aeternum.command.set") && !s.hasPermission("aeternum.command.base"))
                return Collections.emptyList();

            return Arrays.asList("SPRING","SUMMER","AUTUMN","WINTER");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("hud")) {
            if (!s.hasPermission("aeternum.command.hud") && !s.hasPermission("aeternum.command.base"))
                return Collections.emptyList();

            return Arrays.asList("fixed","variable","off");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("biomes")) {
            if (!s.hasPermission("aeternum.command.biomes") && !s.hasPermission("aeternum.command.base"))
                return Collections.emptyList();

            return Arrays.asList("on", "off", "restore");
        }

        return Collections.emptyList();
    }


}
