package Kinkin.aeternum.farming;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.SeasonService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class SeasonalCropGrowthListener implements Listener {

    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;

    private final SeasonalCropConfig config;
    private final GreenhouseService greenhouse;
    private final CropGrowthService cropGrowth;
    private final SeasonalCropLoreListener loreListener;

    public SeasonalCropGrowthListener(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        this.plugin = plugin;
        this.seasons = seasons;

        this.config = new SeasonalCropConfig(plugin);
        this.greenhouse = new GreenhouseService(config);
        this.cropGrowth = new CropGrowthService(config, greenhouse, seasons);
        this.loreListener = new SeasonalCropLoreListener(plugin, config);
    }

    public void register() {
        if (!config.isEnabled()) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        loreListener.register();
    }

    public void unregister() {
        BlockGrowEvent.getHandlerList().unregister(this);
        PlayerInteractEvent.getHandlerList().unregister(this);
        loreListener.unregister();
    }

    public void reloadFromConfig() {
        this.config.reload();
    }

    @EventHandler
    public void onGrow(BlockGrowEvent e) {
        if (!config.isEnabled()) return;

        if (!(e.getNewState().getBlockData() instanceof Ageable age)) {
            return;
        }

        CropGrowthService.GrowthDecision decision = cropGrowth.evaluate(e.getBlock());

        if (decision.cancel()) {
            e.setCancelled(true);
            return;
        }

        int extra = decision.extraAges();
        if (extra <= 0) {
            return; // se deja el crecimiento vanilla tal cual
        }

        int newAge = Math.min(age.getMaximumAge(), age.getAge() + extra);
        age.setAge(newAge);
        e.getNewState().setBlockData(age);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDebugStick(PlayerInteractEvent e) {
        if (!config.isEnabled() || !config.isDebugEnabled()) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;
        if (e.getItem() == null) return;
        if (e.getItem().getType() != config.getDebugStickMaterial()) return;

        Block block = e.getClickedBlock();
        if (!config.isManagedCrop(block.getType())) return;

        boolean inGreenhouse = greenhouse.isInGreenhouse(block);
        Player p = e.getPlayer();
        CalendarState st = seasons.getStateCopy();

        if (inGreenhouse) {
            p.sendMessage(ChatColor.GREEN + "This crop is protected by the greenhouse (" + st.season.name() + ").");
        } else {
            p.sendMessage(ChatColor.RED + "This crop is NOT protected by the greenhouse (" + st.season.name() + ").");
        }
    }
}
