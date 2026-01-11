package Kinkin.aeternum.command;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.lang.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import Kinkin.aeternum.util.BookPaginator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static Kinkin.aeternum.calendar.Season.SPRING;
import static Kinkin.aeternum.calendar.Season.SUMMER;

public final class CmdSeasonGuide implements CommandExecutor, TabCompleter, Listener {

    private final AeternumSeasonsPlugin plugin;
    private final LanguageManager lang;
    private final SeasonService seasonService;

    public CmdSeasonGuide(AeternumSeasonsPlugin plugin,
                          LanguageManager lang,
                          SeasonService seasonService) {
        this.plugin = plugin;
        this.lang = lang;
        this.seasonService = seasonService;

        // Para que podamos escuchar el join y dar el libro a nuevos jugadores
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // --------- COMANDO /season guide ---------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        giveGuide(p, true); // true = vino del comando
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        return List.of();
    }

    // --------- AUTO-LIBRO A JUGADORES NUEVOS ---------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPlayedBefore()) {
            // Primera vez en el servidor → regalamos la guía
            giveGuide(p, false);
        }
    }

    // --------- LÓGICA PARA CREAR Y ENTREGAR EL LIBRO ---------

    private void giveGuide(Player p, boolean fromCommand) {
        Season current = seasonService.getStateCopy().season;

        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) {
            p.sendMessage("§cNo se pudo crear el libro de guía.");
            return;
        }

        meta.setTitle(lang.tr(p, "guide.book_title"));
        meta.setAuthor(lang.tr(p, "guide.book_author"));

        List<String> pages = new ArrayList<>();

        // PÁGINA 1: título + estación actual + intro
        {
            StringBuilder sb = new StringBuilder();

            String rawSeasonName = lang.tr(p, "season." + current.name());
            String coloredSeason = seasonColor(current) + rawSeasonName + "§7";

            sb.append("§0")
                    .append(lang.tr(p, "guide.title"))
                    .append("\n\n");

            sb.append(
                    lang.trf(p, "guide.current",
                            Map.of("season", coloredSeason)
                    )
            ).append("\n\n");

            sb.append("§7").append(lang.tr(p, "guide.intro"));
            pages.add(sb.toString());
        }

        {
            StringBuilder sb = new StringBuilder();
            sb.append("§0").append(lang.tr(p, "guide.section.crops"))
                    .append(" §7(1/2)")
                    .append("\n\n");

            sb.append(seasonColor(SPRING)).append("• ")
                    .append(lang.tr(p, "season.SPRING"))
                    .append("§7: ")
                    .append(lang.tr(p, "guide.crops.SPRING"))
                    .append("\n\n");

            sb.append(seasonColor(SUMMER)).append("• ")
                    .append(lang.tr(p, "season.SUMMER"))
                    .append("§7: ")
                    .append(lang.tr(p, "guide.crops.SUMMER"))
                    .append("\n\n");

            pages.add(sb.toString());
        }

        {
            StringBuilder sb = new StringBuilder();
            sb.append("§0").append(lang.tr(p, "guide.section.crops"))
                    .append(" §7(2/2)")
                    .append("\n\n");

            sb.append(seasonColor(Season.AUTUMN)).append("• ")
                    .append(lang.tr(p, "season.AUTUMN"))
                    .append("§7: ")
                    .append(lang.tr(p, "guide.crops.AUTUMN"))
                    .append("\n\n");

            sb.append(seasonColor(Season.WINTER)).append("• ")
                    .append(lang.tr(p, "season.WINTER"))
                    .append("§7: ")
                    .append(lang.tr(p, "guide.crops.WINTER"))
                    .append("\n\n");

            pages.add(sb.toString());
        }

// MIGRACIÓN ANIMAL (1/2)
        {
            StringBuilder sb = new StringBuilder();
            sb.append("§0")
                    .append(lang.tr(p, "guide.section.migration"))
                    .append(" §7(1/2)")
                    .append("\n\n");

            // Primavera
            {
                Season s = Season.SPRING;
                String color = seasonColor(s);
                sb.append(color).append("• ")
                        .append(lang.tr(p, "season." + s.name()))
                        .append("§7: ")
                        .append(lang.tr(p, "guide.migration." + s.name()))
                        .append("\n\n");
            }

            // Verano
            {
                Season s = Season.SUMMER;
                String color = seasonColor(s);
                sb.append(color).append("• ")
                        .append(lang.tr(p, "season." + s.name()))
                        .append("§7: ")
                        .append(lang.tr(p, "guide.migration." + s.name()))
                        .append("\n\n");
            }

            pages.add(sb.toString());
        }

// MIGRACIÓN ANIMAL (2/2)
        {
            StringBuilder sb = new StringBuilder();
            sb.append("§0")
                    .append(lang.tr(p, "guide.section.migration"))
                    .append(" §7(2/2)")
                    .append("\n\n");

            // Otoño
            {
                Season s = Season.AUTUMN;
                String color = seasonColor(s);
                sb.append(color).append("• ")
                        .append(lang.tr(p, "season." + s.name()))
                        .append("§7: ")
                        .append(lang.tr(p, "guide.migration." + s.name()))
                        .append("\n\n");
            }

            // Invierno
            {
                Season s = Season.WINTER;
                String color = seasonColor(s);
                sb.append(color).append("• ")
                        .append(lang.tr(p, "season." + s.name()))
                        .append("§7: ")
                        .append(lang.tr(p, "guide.migration." + s.name()))
                        .append("\n\n");
            }

            pages.add(sb.toString());
        }

        {
            StringBuilder sb = new StringBuilder();
            sb.append("§0").append(lang.tr(p, "guide.section.weather"))
                    .append(" §7(1/2)")
                    .append("\n\n");

            sb.append(seasonColor(SPRING)).append("• ")
                    .append(lang.tr(p, "season.SPRING"))
                    .append("§7: ")
                    .append(lang.tr(p, "guide.weather.SPRING"))
                    .append("\n\n");

            sb.append(seasonColor(SUMMER)).append("• ")
                    .append(lang.tr(p, "season.SUMMER"))
                    .append("§7: ")
                    .append(lang.tr(p, "guide.weather.SUMMER"))
                    .append("\n\n");

            pages.add(sb.toString());
        }

        {
            StringBuilder sb = new StringBuilder();
            sb.append("§0").append(lang.tr(p, "guide.section.weather"))
                    .append(" §7(2/2)")
                    .append("\n\n");

            sb.append(seasonColor(Season.AUTUMN)).append("• ")
                    .append(lang.tr(p, "season.AUTUMN"))
                    .append("§7: ")
                    .append(lang.tr(p, "guide.weather.AUTUMN"))
                    .append("\n\n");

            sb.append(seasonColor(Season.WINTER)).append("• ")
                    .append(lang.tr(p, "season.WINTER"))
                    .append("§7: ")
                    .append(lang.tr(p, "guide.weather.WINTER"))
                    .append("\n\n");

            sb.append("§7").append(lang.tr(p, "guide.footer"));
            pages.add(sb.toString());
        }


        // --- aplicar páginas y dar el libro ---
        BookPaginator paginator = new BookPaginator();

        boolean first = true;
        for (String rawPage : pages) {
            // Cada entrada de 'pages' debe empezar en una hoja nueva
            if (!first) {
                paginator.newPage();   // <-- salto de página “duro”
            }
            first = false;

            paginator.addText(rawPage);
            // si el texto es largo, el paginator solo se encargará
            // de partirlo en 2+ páginas físicas sin cortar líneas
        }

        List<String> finalPages = paginator.build();

        meta.setPages(finalPages);
        book.setItemMeta(meta);

        p.getInventory().addItem(book);

        if (fromCommand) {
            p.sendMessage("§2" + lang.tr(p, "guide.book_given"));
        } else {
            p.sendMessage("§2" + lang.tr(p, "guide.book_welcome"));
        }
    }

    /** Colores por estación para el texto del libro. */
    private String seasonColor(Season s) {
        return switch (s) {
            case SPRING -> "§2"; // verde
            case SUMMER -> "§6"; // naranja
            case AUTUMN -> "§6"; // naranja otoñal
            case WINTER -> "§3"; // azul frío
        };
    }
}
