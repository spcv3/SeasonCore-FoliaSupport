package Kinkin.aeternum.farming;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;

public final class SeasonalCropLoreListener implements Listener {

    private final AeternumSeasonsPlugin plugin;
    private final SeasonalCropConfig config;
    private final CropLoreService lore;

    public SeasonalCropLoreListener(AeternumSeasonsPlugin plugin, SeasonalCropConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.lore = new CropLoreService(plugin, config);
    }

    public void register() {
        if (!config.isEnabled()) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void unregister() {
        ItemSpawnEvent.getHandlerList().unregister(this);
        EntityPickupItemEvent.getHandlerList().unregister(this);
        CraftItemEvent.getHandlerList().unregister(this);
        LootGenerateEvent.getHandlerList().unregister(this);
        PlayerJoinEvent.getHandlerList().unregister(this);
    }

    // Cuando aparece un item en el mundo (drops, etc.)
    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (!config.isEnabled()) return;
        Item it = e.getEntity();
        ItemStack stack = it.getItemStack();
        if (lore.apply(stack, null)) {
            it.setItemStack(stack);
        }
    }

    // Cuando el jugador lo recoge -> lore en idioma del jugador
    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!config.isEnabled()) return;
        if (!(e.getEntity() instanceof Player p)) return;

        ItemStack stack = e.getItem().getItemStack();
        if (lore.apply(stack, p)) {
            e.getItem().setItemStack(stack);
        }
    }

    // Resultado de crafteo (por si hay semillas que se craftean)
    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!config.isEnabled()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack result = e.getCurrentItem();
        lore.apply(result, p);
    }

    // Loot de cofres/entidades
    @EventHandler(ignoreCancelled = true)
    public void onLoot(LootGenerateEvent e) {
        if (!config.isEnabled()) return;
        e.getLoot().forEach(stack -> lore.apply(stack, null));
    }

    // Inventario viejo al entrar
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!config.isEnabled()) return;
        Player p = e.getPlayer();
        for (ItemStack stack : p.getInventory().getContents()) {
            lore.apply(stack, p);
        }
    }
}
