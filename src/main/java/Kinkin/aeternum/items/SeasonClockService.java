package Kinkin.aeternum.items;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.SeasonService;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public final class SeasonClockService implements Listener {

    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;


    private final NamespacedKey KEY_CLOCK;
    private final NamespacedKey RECIPE_KEY;

    private ItemStack proto;

    public SeasonClockService(AeternumSeasonsPlugin plugin,
                              SeasonService seasons) {
        this.plugin = plugin;
        this.seasons = seasons;
        this.KEY_CLOCK = new NamespacedKey(plugin, "season_clock");
        this.RECIPE_KEY = new NamespacedKey(plugin, "season_clock");
    }

    public void register() {
        buildPrototype();
        registerRecipe();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }


    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    private void buildPrototype() {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) { proto = item; return; }

        // proto base en idioma servidor
        meta.setDisplayName(trServer("items.food.season_clock.name"));
        meta.setLore(List.of(
                trServer("items.food.season_clock.lore_1"),
                trServer("items.food.season_clock.lore_2")
        ));

        // 🔹 Leer CMD de donde exista: survival.yml (si está) o config.yml
        int cmd = 0;
        if (plugin.cfg != null && plugin.cfg.survival != null) {
            cmd = plugin.cfg.survival.getInt("season_clock.custom_model_data", 0);
        } else {
            cmd = plugin.getConfig().getInt("season_clock.custom_model_data", 0);
        }

        if (cmd > 0) meta.setCustomModelData(cmd);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_CLOCK, PersistentDataType.BYTE, (byte) 1);

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        this.proto = item;
    }


    private void registerRecipe() {
        if (proto == null) return;

        ShapedRecipe r = new ShapedRecipe(RECIPE_KEY, proto.clone());
        r.shape(" D ", " C ", "   ");
        r.setIngredient('D', Material.COMPASS);
        r.setIngredient('C', Material.CLOCK);
        Bukkit.addRecipe(r);
    }

    private boolean isSeasonClock(ItemStack item) {
        if (item == null || item.getType() != Material.CLOCK) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Byte v = pdc.get(KEY_CLOCK, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    // ✅ Localiza el item para el jugador
    private void applyClockLocalization(Player p, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        meta.setDisplayName(tr(p, "items.food.season_clock.name"));
        meta.setLore(List.of(
                tr(p, "items.food.season_clock.lore_1"),
                tr(p, "items.food.season_clock.lore_2")
        ));

        item.setItemMeta(meta);
    }

    // ✅ Mesa de crafteo: muestra resultado ya traducido (preview)
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (!(e.getView().getPlayer() instanceof Player p)) return;

        CraftingInventory inv = e.getInventory();
        ItemStack result = inv.getResult();
        if (!isSeasonClock(result)) return;

        ItemStack localized = result.clone();
        applyClockLocalization(p, localized);
        inv.setResult(localized);
    }

    // ✅ Al craftear: asegura idioma correcto en el item final
    @EventHandler
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack result = e.getCurrentItem();
        if (!isSeasonClock(result)) return;

        applyClockLocalization(p, result);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = e.getItem();
        if (!isSeasonClock(item)) return;

        Player p = e.getPlayer();
        e.setCancelled(true);

        sendClockInfo(p);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.6f);
    }

    private void sendClockInfo(Player p) {
        CalendarState st = seasons.getStateCopy();

        p.sendMessage(tr(p, "clock.use.header"));

        Map<String, Object> varsDate = new HashMap<>();
        varsDate.put("day", st.day);
        varsDate.put("max", seasons.getDaysPerSeason());
        String seasonKey = "season." + st.season.name();   // season.SPRING, season.SUMMER, etc.
        varsDate.put("season", tr(p, seasonKey));
        varsDate.put("year", st.year);
        p.sendMessage(trf(p, "clock.use.date", varsDate));


        World w = p.getWorld();
        boolean storm = w.hasStorm();
        int ticks = w.getWeatherDuration();
        long seconds = ticks / 20L;

        Map<String, Object> varsWeather = new HashMap<>();
        varsWeather.put("state", storm
                ? tr(p, "clock.weather.raining")
                : tr(p, "clock.weather.clear"));
        varsWeather.put("minutes", Math.max(1, seconds / 60L));

        // ✅ ahora usa weather_line
        p.sendMessage(trf(p, "clock.use.weather_line", varsWeather));
    }

    private String trServer(String key) { return plugin.lang.trServer(key); }
    private String tr(Player p, String key) { return plugin.lang.tr(p, key); }
    private String trf(Player p, String key, Map<String, Object> vars) {
        return plugin.lang.trf(p, key, vars);
    }


}
