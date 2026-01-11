package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Cocoa;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Control estacional de flora natural:
 * - Spawnea/restaura flora NATURAL fuera de la vista del jugador (anillo).
 * - Limpia flora natural progresivo en estaciones donde no debe existir.
 * - Si una regla está enabled=false y purge_when_disabled=true, purga NATURALES siempre.
 * - Nunca toca bloques colocados por jugadores (place + fertilize marca).
 *
 * MEJORAS:
 *  - pick random de blocks (variedad)
 *  - min_distance_blocks (evita clusters)
 *  - max_per_chunk (cap natural por chunk)
 *  - no procesa el mismo chunk 2 veces en un tick (varios jugadores)
 */
public final class SeasonalFloraController implements Listener, Runnable {

    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;

    private boolean enabled;
    private int tickPeriod;
    private int innerRadiusChunksCfg;
    private int outerRadiusChunksCfg;
    private int budgetPerTick;
    private int maxChunksPerTick;
    private boolean protectPlayerPlaced;

    // config
    private boolean allowInView;
    private int surfaceScanDepth;

    // persistence
    private boolean persistPlacements;
    private int persistAutosaveMinutes;
    private File placementsFile;
    private boolean placementsLoaded;
    private volatile boolean placementsDirty;
    private long nextAutosaveAtMs;

    private BukkitTask task;

    /** Reglas cargadas desde config (incluye purge-only cuando están disabled) */
    private final Map<String, FloraRule> rules = new LinkedHashMap<>();
    /** Lookup rápido por Material -> reglas que lo afectan */
    private final Map<Material, List<FloraRule>> rulesByMaterial = new EnumMap<>(Material.class);

    /**
     * Set de bloques colocados por jugadores para NO tocarlos jamás.
     * Guardado por chunkKey -> set de blockKey.
     */
    private final Map<Long, Set<Long>> playerPlaced = new ConcurrentHashMap<>();

    /**
     * Set de bloques colocados por el plugin (para poder purgarlos sin tocar lo natural ni lo del jugador).
     * Guardado por chunkKey -> set de blockKey.
     */
    private final Map<Long, Set<Long>> pluginPlaced = new ConcurrentHashMap<>();

    private static final int OFFSETS_STEP = 1;        // chunks
    private static final int SAMPLES_PER_CHUNK = 28;  // muestreo por chunk para limpieza

    public SeasonalFloraController(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        this.plugin = plugin;
        this.seasons = seasons;
        this.placementsFile = new File(plugin.getDataFolder(), "seasonal_flora_placements.yml");
        reloadFromConfig();
        loadPlacementsIfNeeded();
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (task != null) task.cancel();
        if (!enabled) return;
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this, 40L, tickPeriod);
    }

    public void unregister() {
        if (task != null) task.cancel();
        task = null;

        // Guarda placements antes de limpiar (para no perder protección / purga tras reinicios)
        savePlacementsIfNeeded(true);

        HandlerList.unregisterAll(this);
        rules.clear();
        rulesByMaterial.clear();
        playerPlaced.clear();
        pluginPlaced.clear();
    }

    public void reloadFromConfig() {
        this.enabled = plugin.cfg.climate.getBoolean("seasonal_flora.enabled", true);
        this.tickPeriod = Math.max(1, plugin.cfg.climate.getInt("seasonal_flora.tick_period_ticks", 10));
        this.innerRadiusChunksCfg = Math.max(0, plugin.cfg.climate.getInt("seasonal_flora.inner_radius_chunks", 0));
        this.outerRadiusChunksCfg = Math.max(1, plugin.cfg.climate.getInt("seasonal_flora.outer_radius_chunks", 8));
        this.budgetPerTick = Math.max(1, plugin.cfg.climate.getInt("seasonal_flora.budget_blocks_per_tick", 120));
        this.maxChunksPerTick = Math.max(1, plugin.cfg.climate.getInt("seasonal_flora.max_chunks_per_tick", 8));
        this.protectPlayerPlaced = plugin.cfg.climate.getBoolean("seasonal_flora.protect_player_placed", true);

        this.allowInView = plugin.cfg.climate.getBoolean("seasonal_flora.allow_in_view", true);
        this.surfaceScanDepth = Math.max(1, plugin.cfg.climate.getInt("seasonal_flora.surface_scan_depth", 8));

        this.persistPlacements = plugin.cfg.climate.getBoolean("seasonal_flora.persist_placements", true);
        this.persistAutosaveMinutes = Math.max(1, plugin.cfg.climate.getInt("seasonal_flora.persist_autosave_minutes", 5));
        if (!persistPlacements) {
            // si lo apagas, por seguridad limpiamos el tracking para no crecer RAM sin necesidad
            playerPlaced.clear();
            pluginPlaced.clear();
            placementsLoaded = false;
            placementsDirty = false;
        } else {
            loadPlacementsIfNeeded();
            long now = System.currentTimeMillis();
            nextAutosaveAtMs = now + (persistAutosaveMinutes * 60_000L);
        }

        rules.clear();
        rulesByMaterial.clear();

        var sec = plugin.cfg.climate.getConfigurationSection("seasonal_flora.rules");
        if (sec == null) {
            plugin.getLogger().info("[SeasonalFlora] No rules found at seasonal_flora.rules");
            return;
        }

        int loaded = 0;
        int purgeOnly = 0;

        for (String id : sec.getKeys(false)) {
            var rsec = sec.getConfigurationSection(id);
            if (rsec == null) continue;

            FloraRule rule = FloraRule.fromConfig(id, rsec, plugin);

            // si está disabled y no quiere purga cuando disabled -> ignorar totalmente
            if (!rule.enabled && !rule.purgeWhenDisabled) continue;

            if (rule.blocks.isEmpty()) {
                plugin.getLogger().warning("[SeasonalFlora] rule '" + id + "' has no blocks, skipped.");
                continue;
            }

            rules.put(id, rule);
            loaded++;
            if (!rule.enabled && rule.purgeWhenDisabled) purgeOnly++;

            for (Material m : rule.blocks) {
                rulesByMaterial.computeIfAbsent(m, k -> new ArrayList<>()).add(rule);
            }
        }

        plugin.getLogger().info("[SeasonalFlora] Loaded " + loaded + " flora rules (" + purgeOnly + " purge-only).");
    }

    /* ============================= EVENTS ============================= */

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!enabled || !protectPlayerPlaced) return;
        Material type = e.getBlockPlaced().getType();
        if (!rulesByMaterial.containsKey(type)) return;

        Block b = e.getBlockPlaced();
        markPlayerPlaced(b);

        // si es planta doble, marcamos arriba también
        if (isDoublePlant(type)) {
            Block up = b.getRelative(BlockFace.UP);
            if (up.getType() == type) markPlayerPlaced(up);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent e) {
        if (!enabled) return;

        Material newType = e.getNewState().getType();
        List<FloraRule> list = rulesByMaterial.get(newType);
        if (list == null) return;

        Season s = seasons.getStateCopy().season;

        Location nl = e.getNewState().getLocation();
        Biome nb = nl.getWorld().getBiome(nl.getBlockX(), nl.getBlockY(), nl.getBlockZ());

        for (FloraRule r : list) {
            if (!r.enabled) continue; // reglas off no bloquean spread vanilla
            if (!r.spreadSeasons.contains(s) || !r.isBiomeAllowed(nb)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (!enabled || !protectPlayerPlaced) return;
        Material type = e.getBlock().getType();
        if (!rulesByMaterial.containsKey(type)) return;

        Block b = e.getBlock();
        unmarkPlayerPlaced(b);

        if (isDoublePlant(type)) {
            Block up = b.getRelative(BlockFace.UP);
            if (up.getType() == type) unmarkPlayerPlaced(up);
            Block down = b.getRelative(BlockFace.DOWN);
            if (down.getType() == type) unmarkPlayerPlaced(down);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockFertilize(BlockFertilizeEvent e) {
        if (!enabled) return;

        Season s = seasons.getStateCopy().season;

        for (BlockState bs : e.getBlocks()) {
            List<FloraRule> list = rulesByMaterial.get(bs.getType());
            if (list == null) continue;

            Location l = bs.getLocation();
            Biome b = l.getWorld().getBiome(l.getBlockX(), l.getBlockY(), l.getBlockZ());

            for (FloraRule r : list) {
                if (!r.enabled) continue; // off no bloquea
                if (!r.spreadSeasons.contains(s) || !r.isBiomeAllowed(b)) {
                    e.setCancelled(true);
                    return;
                }
            }
        }

        // si fue fertilizado por jugador, marcamos como "playerPlaced"
        if (protectPlayerPlaced && e.getPlayer() != null) {
            for (BlockState bs : e.getBlocks()) {
                Block placed = bs.getBlock();
                if (rulesByMaterial.containsKey(placed.getType())) {
                    markPlayerPlaced(placed);
                    if (isDoublePlant(placed.getType())) {
                        Block up = placed.getRelative(BlockFace.UP);
                        if (up.getType() == placed.getType()) markPlayerPlaced(up);
                    }
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent e) {
        if (!enabled) return;

        Material type = e.getNewState().getType();
        List<FloraRule> list = rulesByMaterial.get(type);
        if (list == null) return;

        Season s = seasons.getStateCopy().season;

        Location l = e.getBlock().getLocation();
        Biome b = l.getWorld().getBiome(l.getBlockX(), l.getBlockY(), l.getBlockZ());

        for (FloraRule r : list) {
            if (!r.enabled) continue; // off no bloquea grow vanilla
            if (!r.spreadSeasons.contains(s) || !r.isBiomeAllowed(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // Backup para bonemeal directo por interact
    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (!enabled) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.BONE_MEAL) return;

        Block b = e.getClickedBlock();
        if (b == null) return;

        List<FloraRule> list = rulesByMaterial.get(b.getType());
        if (list == null) return;

        Season s = seasons.getStateCopy().season;
        for (FloraRule r : list) {
            if (!r.enabled) continue;
            if (!r.spreadSeasons.contains(s)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /* ============================= RUNNER ============================= */

    @Override
    public void run() {
        if (!enabled || rules.isEmpty()) return;

        CalendarState st = seasons.getStateCopy();
        Season season = st.season;

        int budget = budgetPerTick;
        if (budget <= 0) return;

        // evita procesar el mismo chunk 2 veces este tick (por donuts solapados)
        Set<Long> processedThisTick = new HashSet<>();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (budget <= 0) break;
            if (processedThisTick.size() >= maxChunksPerTick) break;

            World w = p.getWorld();
            if (w.getEnvironment() != World.Environment.NORMAL) continue;

            int outer = Math.max(outerRadiusChunksCfg, 1);
            if (allowInView) {
                int view = Bukkit.getViewDistance();
                // que outer nunca sea mayor al view distance (para que el chunk exista cargado)
                outer = Math.min(outer, view);
            }

// inner no puede pasar de outer-1
            int inner = Math.min(innerRadiusChunksCfg, Math.max(0, outer - 1));

            Location loc = p.getLocation();
            int pcx = loc.getBlockX() >> 4;
            int pcz = loc.getBlockZ() >> 4;

            // Donut: solo distancias [inner..outer]
            for (int dist = inner; dist <= outer && budget > 0; dist++) {
                if (processedThisTick.size() >= maxChunksPerTick) break;
                for (int dx = -dist; dx <= dist && budget > 0; dx += OFFSETS_STEP) {
                    if (processedThisTick.size() >= maxChunksPerTick) break;
                    for (int dz = -dist; dz <= dist && budget > 0; dz += OFFSETS_STEP) {
                        if (processedThisTick.size() >= maxChunksPerTick) break;
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != dist) continue;

                        int cx = pcx + dx;
                        int cz = pcz + dz;
                        if (!w.isChunkLoaded(cx, cz)) continue;

                        long ck = chunkKey(w, cx, cz);
                        if (!processedThisTick.add(ck)) continue; // ya processado este tick

                        Chunk ch = w.getChunkAt(cx, cz);
                        budget = processChunk(ch, season, budget);
                    }
                }
            }
        }

        autosaveMaybe();
    }

    private int processChunk(Chunk ch, Season season, int budget) {
        if (budget <= 0) return 0;

        World w = ch.getWorld();
        int bx = ch.getX() << 4;
        int bz = ch.getZ() << 4;

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // 1) Limpieza/reemplazo natural progresivo
        for (int i = 0; i < SAMPLES_PER_CHUNK && budget > 0; i++) {
            int x = bx + rnd.nextInt(16);
            int z = bz + rnd.nextInt(16);
            int y = w.getHighestBlockYAt(x, z);

            // Si arriba hay nieve / hojas / etc, baja un poco para encontrar la flora real.
            // Esto evita que "la nieve tape" flores y nunca se purguen.
            Block b = findPurgeCandidate(w, x, y, z);
            if (b == null) continue;

            Material type = b.getType();
            List<FloraRule> list = rulesByMaterial.get(type);
            if (list == null) continue;

            for (FloraRule r : list) {

                boolean purgeSeason = r.enabled && r.removeSeasons.contains(season);
                boolean purgeDisabled = (!r.enabled && r.purgeWhenDisabled);
                if (!purgeSeason && !purgeDisabled) continue;

                // Nunca tocar lo del jugador
                if (isProtectedByPlayer(b)) continue;

                // Purga SOLO lo que puso el plugin (y opcionalmente lo natural si purge_natural=true)
                boolean isPlugin = isPlacedByPlugin(b);
                boolean canPurge = (r.purgePluginPlaced && isPlugin) || (r.purgeNatural && !isPlugin);
                if (!canPurge) continue;

                Material replace = (r.replaceWith != null) ? r.replaceWith : Material.AIR;
                int removed = purgeBlockRespectingShape(b, replace);
                if (removed > 0) {
                    budget -= removed;
                }
                break;
            }
        }

        if (budget <= 0) return budget;

        // 2) Restauración / spawn probabilístico (solo reglas enabled)
        for (FloraRule r : rules.values()) {
            if (budget <= 0) break;
            if (!r.enabled) continue;
            if (!r.restoreSeasons.contains(season)) continue;

            if (rnd.nextDouble() > r.restoreChance) continue;

            // cap por chunk (si aplica)
            if (r.maxPerChunk > 0) {
                int existing = countRuleBlocksInChunk(ch, r.blocks, r.maxPerChunk);
                if (existing >= r.maxPerChunk) continue;
            }

            for (int tries = 0; tries < r.restoreTriesPerChunk && budget > 0; tries++) {
                int x = bx + rnd.nextInt(16);
                int z = bz + rnd.nextInt(16);
                int y = w.getHighestBlockYAt(x, z);

                Block top = w.getBlockAt(x, y, z);
                if (top.isLiquid()) continue;

                Block placeAt = top.getType().isAir() ? top : top.getRelative(BlockFace.UP);
                if (!placeAt.getType().isAir()) continue;
                if (isProtectedByPlayer(placeAt)) continue;

                Biome biome = w.getBiome(x, placeAt.getY(), z);
                if (!r.isBiomeAllowed(biome)) continue;

                int light = placeAt.getLightLevel();
                if (light < r.minLight || light > r.maxLight) continue;

                Block ground = placeAt.getRelative(BlockFace.DOWN);
                if (r.requireSolidGround) {
                    if (!ground.getType().isSolid()) continue;
                    if (!r.groundBlocks.isEmpty() && !r.groundBlocks.contains(ground.getType())) continue;
                }

                if (r.forbidCanopy) {
                    if (!isOpenSky(placeAt, r.canopyCheckHeight)) continue;
                }

                // evita clusters (si aplica)
                if (r.minDistanceBlocks > 0 && hasNearbyRuleBlock(placeAt, r.blocks, r.minDistanceBlocks)) {
                    continue;
                }

                boolean placed = placeRuleBlock(r, placeAt, rnd);
                if (placed) {
                    budget--;
                    break;
                }
            }
        }

        return budget;
    }

    /** Cuenta flora de la regla en chunk, parando cuando llega al cap. */
    private int countRuleBlocksInChunk(Chunk ch, List<Material> mats, int cap) {
        World w = ch.getWorld();
        int bx = ch.getX() << 4;
        int bz = ch.getZ() << 4;

        int count = 0;
        int scan = Math.max(1, surfaceScanDepth);

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = bx + dx;
                int z = bz + dz;

                int surfaceY = w.getHighestBlockYAt(x, z);
                int maxY = Math.min(w.getMaxHeight() - 1, surfaceY + 2);
                int minY = Math.max(w.getMinHeight(), surfaceY - scan);

                for (int y = maxY; y >= minY; y--) {
                    Material t = w.getBlockAt(x, y, z).getType();
                    if (mats.contains(t)) {
                        count++;
                        if (count >= cap) return count;
                        break; // solo 1 conteo por columna para ser barato
                    }
                    if (t == Material.SNOW || t == Material.SNOW_BLOCK) continue;
                }
            }
        }
        return count;
    }

    /** Checa cerca para no apilar flores igualitas. */
    private boolean hasNearbyRuleBlock(Block center, List<Material> mats, int radius) {
        World w = center.getWorld();
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();

        int r = Math.min(radius, 8); // safety hardcap
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                // circular-ish
                if (dx*dx + dz*dz > r*r) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    Block b = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (mats.contains(b.getType())) return true;
                }
            }
        }
        return false;
    }

    private boolean placeRuleBlock(FloraRule r, Block placeAt, ThreadLocalRandom rnd) {
        // ✅ random real del bloque a poner
        Material toPlace = r.blocks.size() == 1
                ? r.blocks.get(0)
                : r.blocks.get(rnd.nextInt(r.blocks.size()));

        if (r.requiresAttachment && toPlace == Material.COCOA) {
            boolean ok = placeCocoa(r, placeAt, rnd);
            if (ok) markPluginPlaced(placeAt);
            return ok;
        }

        if (r.doublePlant && isDoublePlant(toPlace)) {
            Block up = placeAt.getRelative(BlockFace.UP);
            if (!up.getType().isAir()) return false;
            placeDoublePlant(placeAt, toPlace);
            markPluginPlaced(placeAt);
            markPluginPlaced(up);
            return true;
        }

        placeAt.setType(toPlace, false);
        markPluginPlaced(placeAt);
        return true;
    }

    private boolean placeCocoa(FloraRule r, Block placeAt, ThreadLocalRandom rnd) {
        List<BlockFace> faces = new ArrayList<>(List.of(
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
        ));
        Collections.shuffle(faces, rnd);

        for (BlockFace face : faces) {
            Block attached = placeAt.getRelative(face);
            if (r.attachBlocks.isEmpty() || r.attachBlocks.contains(attached.getType())) {
                placeAt.setType(Material.COCOA, false);
                BlockData bd = placeAt.getBlockData();
                if (bd instanceof Cocoa cocoa) {
                    cocoa.setFacing(face.getOppositeFace());
                    cocoa.setAge(0);
                    placeAt.setBlockData(cocoa, false);
                }
                return true;
            }
        }
        return false;
    }

    private void placeDoublePlant(Block lower, Material mat) {
        lower.setType(mat, false);
        Block upper = lower.getRelative(BlockFace.UP);
        upper.setType(mat, false);

        BlockData lowerData = lower.getBlockData();
        BlockData upperData = upper.getBlockData();
        if (lowerData instanceof Bisected bl && upperData instanceof Bisected bu) {
            bl.setHalf(Bisected.Half.BOTTOM);
            bu.setHalf(Bisected.Half.TOP);
            lower.setBlockData(bl, false);
            upper.setBlockData(bu, false);
        }
    }

    private boolean isOpenSky(Block placeAt, int height) {
        Block b = placeAt;
        for (int i = 0; i < height; i++) {
            b = b.getRelative(BlockFace.UP);
            Material t = b.getType();
            if (!t.isAir() && !t.isTransparent()) return false;
        }
        return true;
    }

    private boolean isDoublePlant(Material m) {
        String n = m.name();
        return n.equals("SUNFLOWER") || n.equals("ROSE_BUSH") || n.equals("LILAC") || n.equals("PEONY");
    }

    /**
     * Intenta encontrar el bloque real a evaluar para purge (por si arriba hay nieve / canopy).
     * Escanea hacia abajo unos pocos bloques: barato y suficiente.
     */
    private Block findPurgeCandidate(World w, int x, int surfaceY, int z) {
        // surfaceY viene de getHighestBlockYAt (normalmente el bloque sólido del suelo),
        // pero la flora suele estar en surfaceY+1. Por eso escaneamos un rango vertical.
        int maxY = Math.min(w.getMaxHeight() - 1, surfaceY + 2);
        int minY = Math.max(w.getMinHeight(), surfaceY - Math.max(1, surfaceScanDepth));

        for (int y = maxY; y >= minY; y--) {
            Block b = w.getBlockAt(x, y, z);
            Material t = b.getType();

            // ignora nieve (para que no "tape" la detección de flora)
            if (t == Material.SNOW || t == Material.SNOW_BLOCK) continue;

            if (rulesByMaterial.containsKey(t)) return b;
        }

        return null;
    }

    private boolean isProtectedByPlayer(Block b) {
        if (!protectPlayerPlaced) return false;
        long ck = chunkKey(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
        Set<Long> set = playerPlaced.get(ck);
        if (set == null || set.isEmpty()) return false;
        long bk = blockKey(b.getWorld(), b.getX(), b.getY(), b.getZ());
        return set.contains(bk);
    }

    private boolean isPlacedByPlugin(Block b) {
        long ck = chunkKey(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
        Set<Long> set = pluginPlaced.get(ck);
        if (set == null || set.isEmpty()) return false;
        long bk = blockKey(b.getWorld(), b.getX(), b.getY(), b.getZ());
        return set.contains(bk);
    }

    private void markPluginPlaced(Block b) {
        long ck = chunkKey(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
        long bk = blockKey(b.getWorld(), b.getX(), b.getY(), b.getZ());
        boolean changed = pluginPlaced.computeIfAbsent(ck, k -> ConcurrentHashMap.newKeySet()).add(bk);
        if (changed) placementsDirty = true;
    }

    private void unmarkPluginPlaced(Block b) {
        long ck = chunkKey(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
        long bk = blockKey(b.getWorld(), b.getX(), b.getY(), b.getZ());

        Set<Long> set = pluginPlaced.get(ck);
        if (set != null) {
            boolean changed = set.remove(bk);
            if (changed) placementsDirty = true;
            if (set.isEmpty()) pluginPlaced.remove(ck);
        }
    }

    /**
     * Purga el bloque y, si es doble planta, purga ambas mitades. Devuelve cuántos bloques cambió.
     */
    private int purgeBlockRespectingShape(Block b, Material replaceWith) {
        Material type = b.getType();
        int changed = 0;

        if (isDoublePlant(type)) {
            // Para doble planta, elimina arriba y abajo si existen.
            Block up = b.getRelative(BlockFace.UP);
            Block down = b.getRelative(BlockFace.DOWN);

            if (up.getType() == type) {
                up.setType(replaceWith, false);
                unmarkPluginPlaced(up);
                changed++;
            }
            if (down.getType() == type) {
                down.setType(replaceWith, false);
                unmarkPluginPlaced(down);
                changed++;
            }

            // y el actual
            b.setType(replaceWith, false);
            unmarkPluginPlaced(b);
            changed++;
            return changed;
        }

        // Normal
        b.setType(replaceWith, false);
        unmarkPluginPlaced(b);
        return 1;
    }

    private void markPlayerPlaced(Block b) {
        long ck = chunkKey(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
        long bk = blockKey(b.getWorld(), b.getX(), b.getY(), b.getZ());
        boolean changed = playerPlaced.computeIfAbsent(ck, k -> ConcurrentHashMap.newKeySet()).add(bk);
        if (changed) placementsDirty = true;
    }

    private void unmarkPlayerPlaced(Block b) {
        long ck = chunkKey(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
        long bk = blockKey(b.getWorld(), b.getX(), b.getY(), b.getZ());

        Set<Long> set = playerPlaced.get(ck);
        if (set != null) {
            boolean changed = set.remove(bk);
            if (changed) placementsDirty = true;
            if (set.isEmpty()) playerPlaced.remove(ck);
        }
    }

    private long chunkKey(World w, int cx, int cz) {
        long k = (((long) cx) & 0xffffffffL) << 32 | (((long) cz) & 0xffffffffL);
        long wh = (w.getUID().getMostSignificantBits() ^ w.getUID().getLeastSignificantBits());
        return k ^ wh;
    }

    private long blockKey(World w, int x, int y, int z) {
        long wx = x & 0x3FFFFFFL;
        long wz = z & 0x3FFFFFFL;
        long wy = (y + 2048L) & 0xFFFL;
        long k = (wx << 38) | (wz << 12) | wy;
        long wh = (w.getUID().getMostSignificantBits() ^ w.getUID().getLeastSignificantBits());
        return k ^ wh;
    }

    /* ============================= RULE ============================= */



    // ---------------------------------------------------------------------
    // Persistence (playerPlaced / pluginPlaced)
    // ---------------------------------------------------------------------

    private void autosaveMaybe() {
        if (!persistPlacements) return;
        if (!placementsDirty) return;
        long now = System.currentTimeMillis();
        if (now < nextAutosaveAtMs) return;
        savePlacementsIfNeeded(false);
        nextAutosaveAtMs = now + (persistAutosaveMinutes * 60_000L);
    }

    private void loadPlacementsIfNeeded() {
        if (!persistPlacements) return;
        if (placementsLoaded) return;
        placementsLoaded = true;

        try {
            if (placementsFile == null) placementsFile = new File(plugin.getDataFolder(), "seasonal_flora_placements.yml");
            if (!placementsFile.exists()) return;

            YamlConfiguration yml = YamlConfiguration.loadConfiguration(placementsFile);

            var psec = yml.getConfigurationSection("player");
            if (psec != null) {
                for (String ckStr : psec.getKeys(false)) {
                    long ck;
                    try { ck = Long.parseLong(ckStr); } catch (NumberFormatException ex) { continue; }

                    List<String> list = psec.getStringList(ckStr);
                    if (list == null || list.isEmpty()) continue;

                    Set<Long> set = ConcurrentHashMap.newKeySet();
                    for (String s : list) {
                        try { set.add(Long.parseLong(s)); } catch (NumberFormatException ignored) {}
                    }
                    if (!set.isEmpty()) playerPlaced.put(ck, set);
                }
            }

            var gsec = yml.getConfigurationSection("plugin");
            if (gsec != null) {
                for (String ckStr : gsec.getKeys(false)) {
                    long ck;
                    try { ck = Long.parseLong(ckStr); } catch (NumberFormatException ex) { continue; }

                    List<String> list = gsec.getStringList(ckStr);
                    if (list == null || list.isEmpty()) continue;

                    Set<Long> set = ConcurrentHashMap.newKeySet();
                    for (String s : list) {
                        try { set.add(Long.parseLong(s)); } catch (NumberFormatException ignored) {}
                    }
                    if (!set.isEmpty()) pluginPlaced.put(ck, set);
                }
            }

            placementsDirty = false;

        } catch (Exception ex) {
            plugin.getLogger().warning("[SeasonalFlora] Failed to load placements: " + ex.getMessage());
        }
    }

    private void savePlacementsIfNeeded(boolean force) {
        if (!persistPlacements) return;
        if (!force && !placementsDirty) return;

        try {
            File dir = plugin.getDataFolder();
            if (!dir.exists() && !dir.mkdirs()) {
                plugin.getLogger().warning("[SeasonalFlora] Could not create plugin data folder to save placements.");
                return;
            }
            if (placementsFile == null) placementsFile = new File(dir, "seasonal_flora_placements.yml");

            // snapshot para que el I/O no pelee con escrituras concurrentes
            Map<Long, Set<Long>> playerSnap = new HashMap<>();
            for (var e : playerPlaced.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                playerSnap.put(e.getKey(), new HashSet<>(e.getValue()));
            }

            Map<Long, Set<Long>> pluginSnap = new HashMap<>();
            for (var e : pluginPlaced.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                pluginSnap.put(e.getKey(), new HashSet<>(e.getValue()));
            }

            YamlConfiguration yml = new YamlConfiguration();
            yml.set("version", 1);
            yml.set("saved_at", System.currentTimeMillis());

            var psec = yml.createSection("player");
            for (var e : playerSnap.entrySet()) {
                List<String> list = new ArrayList<>(e.getValue().size());
                for (Long v : e.getValue()) list.add(Long.toString(v));
                psec.set(Long.toString(e.getKey()), list);
            }

            var gsec = yml.createSection("plugin");
            for (var e : pluginSnap.entrySet()) {
                List<String> list = new ArrayList<>(e.getValue().size());
                for (Long v : e.getValue()) list.add(Long.toString(v));
                gsec.set(Long.toString(e.getKey()), list);
            }

            yml.save(placementsFile);
            placementsDirty = false;

        } catch (IOException ex) {
            plugin.getLogger().warning("[SeasonalFlora] Failed to save placements: " + ex.getMessage());
        }
    }
    private static final class FloraRule {
        final String id;
        final boolean enabled;
        final boolean purgeWhenDisabled;
        final List<Material> blocks;
        final EnumSet<Season> spreadSeasons;
        final EnumSet<Season> removeSeasons;
        final boolean purgeNatural;
        final boolean purgePluginPlaced;
        final Material replaceWith;
        final EnumSet<Season> restoreSeasons;
        final double restoreChance;
        final int restoreTriesPerChunk;
        final Set<Biome> biomes;
        final int minLight;
        final int maxLight;

        final boolean requireSolidGround;
        final Set<Material> groundBlocks;

        final boolean forbidCanopy;
        final int canopyCheckHeight;

        final boolean doublePlant;

        final boolean requiresAttachment;
        final Set<Material> attachBlocks;

        // ✅ NUEVO: control de densidad natural
        final int maxPerChunk;         // 0 = sin cap
        final int minDistanceBlocks;   // 0 = sin distancia mínima

        FloraRule(
                String id,
                boolean enabled,
                boolean purgeWhenDisabled,
                List<Material> blocks,
                EnumSet<Season> spreadSeasons,
                EnumSet<Season> removeSeasons,
                boolean purgeNatural,
                boolean purgePluginPlaced,
                Material replaceWith,
                EnumSet<Season> restoreSeasons,
                double restoreChance,
                int restoreTriesPerChunk,
                Set<Biome> biomes,
                int minLight,
                int maxLight,
                boolean requireSolidGround,
                Set<Material> groundBlocks,
                boolean forbidCanopy,
                int canopyCheckHeight,
                boolean doublePlant,
                boolean requiresAttachment,
                Set<Material> attachBlocks,
                int maxPerChunk,
                int minDistanceBlocks
        ) {
            this.id = id;
            this.enabled = enabled;
            this.purgeWhenDisabled = purgeWhenDisabled;
            this.blocks = blocks;
            this.spreadSeasons = spreadSeasons;
            this.removeSeasons = removeSeasons;
            this.purgeNatural = purgeNatural;
            this.purgePluginPlaced = purgePluginPlaced;
            this.replaceWith = replaceWith;
            this.restoreSeasons = restoreSeasons;
            this.restoreChance = restoreChance;
            this.restoreTriesPerChunk = restoreTriesPerChunk;
            this.biomes = biomes;
            this.minLight = minLight;
            this.maxLight = maxLight;

            this.requireSolidGround = requireSolidGround;
            this.groundBlocks = groundBlocks;

            this.forbidCanopy = forbidCanopy;
            this.canopyCheckHeight = canopyCheckHeight;

            this.doublePlant = doublePlant;

            this.requiresAttachment = requiresAttachment;
            this.attachBlocks = attachBlocks;

            this.maxPerChunk = maxPerChunk;
            this.minDistanceBlocks = minDistanceBlocks;
        }

        boolean isBiomeAllowed(Biome b) {
            return biomes == null || biomes.isEmpty() || biomes.contains(b);
        }

        static FloraRule fromConfig(String id, org.bukkit.configuration.ConfigurationSection sec, AeternumSeasonsPlugin plugin) {
            boolean enabled = sec.getBoolean("enabled", true);
            boolean purgeWhenDisabled = sec.getBoolean("purge_when_disabled", false);

            List<Material> blocks = new ArrayList<>();
            for (String s : sec.getStringList("blocks")) {
                Material m = parseMaterial(s);
                if (m != null) blocks.add(m);
            }

            EnumSet<Season> spread = parseSeasons(sec.getStringList("spread_seasons"), EnumSet.allOf(Season.class));
            EnumSet<Season> remove = parseSeasons(sec.getStringList("remove_seasons"), EnumSet.noneOf(Season.class));

            boolean purge = sec.getBoolean("purge_natural", false);
            boolean purgePluginPlaced = sec.getBoolean("purge_plugin_placed", true);

            Material replaceWith = parseMaterial(sec.getString("replace_with", "AIR"));
            EnumSet<Season> restore = parseSeasons(sec.getStringList("restore_seasons"), EnumSet.noneOf(Season.class));
            double chance = clamp01(sec.getDouble("restore_chance", 0.0));
            int restoreTries = Math.max(1, sec.getInt("restore_tries_per_chunk", 6));

            Set<Biome> biomes = new HashSet<>();
            for (String s : sec.getStringList("biomes")) {
                Biome b = parseBiome(s);
                if (b != null) biomes.add(b);
            }

            int minLight = Math.max(0, sec.getInt("min_light", 0));
            int maxLight = Math.min(15, sec.getInt("max_light", 15));

            boolean requireSolidGround = sec.getBoolean("require_solid_ground", true);
            Set<Material> groundBlocks = new HashSet<>();
            for (String s : sec.getStringList("ground_blocks")) {
                Material m = parseMaterial(s);
                if (m != null) groundBlocks.add(m);
            }

            boolean forbidCanopy = sec.getBoolean("forbid_canopy", false);
            int canopyCheckHeight = Math.max(1, sec.getInt("canopy_check_height", 3));

            boolean doublePlant = sec.getBoolean("double_plant", false);

            boolean requiresAttachment = sec.getBoolean("requires_attachment", false);
            Set<Material> attachBlocks = new HashSet<>();
            for (String s : sec.getStringList("attach_blocks")) {
                Material m = parseMaterial(s);
                if (m != null) attachBlocks.add(m);
            }

            // ✅ NUEVO (opcionales)
            int maxPerChunk = Math.max(0, sec.getInt("max_per_chunk", 0));           // 0 = ilimitado
            int minDistanceBlocks = Math.max(0, sec.getInt("min_distance_blocks", 0));

            if (blocks.isEmpty()) {
                plugin.getLogger().warning("[SeasonalFlora] rule '" + id + "' has invalid blocks.");
            }

            return new FloraRule(
                    id, enabled, purgeWhenDisabled, blocks, spread, remove, purge, purgePluginPlaced, replaceWith, restore, chance,
                    restoreTries, biomes, minLight, maxLight,
                    requireSolidGround, groundBlocks,
                    forbidCanopy, canopyCheckHeight,
                    doublePlant,
                    requiresAttachment, attachBlocks,
                    maxPerChunk, minDistanceBlocks
            );
        }

        private static Material parseMaterial(String s) {
            if (s == null) return null;
            try {
                return Material.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        private static Biome parseBiome(String s) {
            if (s == null) return null;
            try {
                return Biome.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        private static EnumSet<Season> parseSeasons(List<String> list, EnumSet<Season> def) {
            if (list == null || list.isEmpty()) return def;
            EnumSet<Season> set = EnumSet.noneOf(Season.class);
            for (String s : list) {
                try {
                    set.add(Season.valueOf(s.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {}
            }
            return set.isEmpty() ? def : set;
        }

        private static double clamp01(double d) {
            if (d < 0.0) return 0.0;
            if (d > 1.0) return 1.0;
            return d;
        }
    }
}
