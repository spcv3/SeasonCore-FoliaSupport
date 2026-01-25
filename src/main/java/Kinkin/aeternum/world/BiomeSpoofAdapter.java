package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import Kinkin.aeternum.calendar.SeasonUpdateEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adaptador de biomas para dar el efecto de estaciones:
 * - Tiñe biomas alrededor del jugador según la estación.
 * - Evita barridos: no hace revert global en cambio de estación.
 * - Pinta primero el chunk del jugador y los chunks delante de su vista.
 * - Tras pintar, hace refreshChunk del chunk para que el cliente vea el cambio
 *   sin necesidad de reconectar.
 *
 * + NUEVO:
 * - Soporte para océanos (y opcional ríos), progresivo y con el mismo budget.
 */
public final class BiomeSpoofAdapter implements Listener, Runnable {

    public enum Mode {
        GLOBAL_RING,   // tiñe alrededor de los jugadores
        OFF
    }

    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;

    private Mode mode;
    private int radiusChunksCfg;
    private int budgetPerTick;
    private long tickPeriod;
    private int eventRadiusChunks;

    // config legacy (solo para logging, ya no hacemos revert global en cambio de estación)
    private boolean revertOnSeasonChange;

    // bioma objetivo por estación (desde config)
    private final EnumMap<Season, Biome> seasonTarget = new EnumMap<>(Season.class);

    /* =================== OCEANS + SHORES + RIVERS =================== */
    private boolean oceansEnabled;
    private boolean oceansAffectRivers;
    private boolean oceansAffectShores;        // NUEVO
    private boolean oceansKeepDeepVariants;
    private boolean prioritizeView;

    private boolean riversEnabled;            // NUEVO
    private final EnumMap<Season, Biome> oceanTarget = new EnumMap<>(Season.class);
    private final EnumMap<Season, Biome> riverTarget = new EnumMap<>(Season.class); // NUEVO
    /* ================================================================ */

    private WrappedTask task;

    /**
     * backups: por cada chunk spoofeado guardamos una copia "original"
     * (muestra de biomas antes de empezar con el sistema de estaciones).
     * Así podemos revertir TODO al desregistrar el adaptador
     * o cuando se descarga el chunk.
     */
    private final Map<Long, Biome[]> backups = new ConcurrentHashMap<>();

    /**
     * chunks que actualmente están "spoofeados" (tiñendo biomas por estación).
     */
    private final Set<Long> spoofed = ConcurrentHashMap.newKeySet();
    private static final Set<Long> COLD_CHUNKS = ConcurrentHashMap.newKeySet();

    /* ================== anti-flicker: nudges por jugador ================== */
    private static final int NUDGES_PER_TICK = 8;
    private static final long NUDGE_COOLDOWN_MS = 3000L;
    private static final Material NUDGE_FAKE = Material.BARRIER;
    private static final int STEP_XZ = 4;
    private static final int STEP_Y  = 4;

    private final Map<UUID, java.util.concurrent.ConcurrentLinkedDeque<Long>> nudgeQueue = new ConcurrentHashMap<>();
    private final Map<String, Long> nudgeLast = new ConcurrentHashMap<>();
    /* ===================================================================== */

    /* ===== Modo transición de estación (boost temporal de presupuesto) ==== */
    private static final long TRANSITION_WINDOW_MS = 5000L;
    private static final int TRANSITION_BUDGET_MULTIPLIER = 3;
    private volatile long seasonTransitionUntil = 0L;
    /* ===================================================================== */

    /* ===== Transición suave a final de estación (26, 27, 28) ============= */
    private static final int PRE_TRANSITION_DAYS = 3;
    /* ===================================================================== */

    private final Kinkin.aeternum.world.BiomeBackupStore diskBackups;
    private final Map<Integer, List<Offset>> offsetCache = new ConcurrentHashMap<>();
    private final Map<Long, Family> familyCache = new ConcurrentHashMap<>();
    private final Map<Long, Biome> originalBiomeCache = new ConcurrentHashMap<>();
    private final Map<Long, Biome> lastAppliedTarget = new ConcurrentHashMap<>();
    private final Queue<ChunkRef> pendingChunks = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Set<Long> pendingKeys = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastPlayerChunk = new ConcurrentHashMap<>();



    /**
     * Offset de chunk relativo al jugador, usado para ordenar
     * por distancia y dirección de mirada.
     */
    private static final class Offset {
        final int dx, dz;
        final int dist;            // distancia Chebyshev
        final double invLen;       // 1/len para score forward sin crear Vectors

        Offset(int dx, int dz, int dist, double invLen) {
            this.dx = dx;
            this.dz = dz;
            this.dist = dist;
            this.invLen = invLen;
        }
    }

    private enum Family {
        LAND,
        OCEAN,
        RIVER
    }

    private static final class ChunkRef {
        final UUID worldId;
        final int cx;
        final int cz;
        final long key;

        ChunkRef(UUID worldId, int cx, int cz, long key) {
            this.worldId = worldId;
            this.cx = cx;
            this.cz = cz;
            this.key = key;
        }
    }

    public BiomeSpoofAdapter(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        this.plugin = plugin;
        this.seasons = seasons;
        this.diskBackups = new Kinkin.aeternum.world.BiomeBackupStore(plugin);
        reloadFromConfig();
    }

    private void reloadFromConfig() {
        String m = plugin.cfg.climate.getString("biome_spoof.mode", "GLOBAL_RING");
        if ("OFF".equalsIgnoreCase(m)) {
            this.mode = Mode.OFF;
        } else {
            this.mode = Mode.GLOBAL_RING;
        }

        this.radiusChunksCfg = Math.max(1, plugin.cfg.climate.getInt("biome_spoof.radius_chunks", 8));
        this.budgetPerTick   = Math.max(2, plugin.cfg.climate.getInt("biome_spoof.budget_chunks_per_tick", 16));
        this.tickPeriod      = Math.max(5L, plugin.cfg.climate.getLong("biome_spoof.tick_period_ticks", 20L));
        this.eventRadiusChunks = Math.max(0, plugin.cfg.climate.getInt("biome_spoof.event_radius_chunks", 2));
        this.revertOnSeasonChange = plugin.cfg.climate.getBoolean("biome_spoof.revert_on_non_winter", true);

        seasonTarget.put(Season.SPRING,  readBiome("biome_spoof.seasons.SPRING",  Biome.FLOWER_FOREST));
        seasonTarget.put(Season.SUMMER,  readBiome("biome_spoof.seasons.SUMMER",  Biome.PLAINS));
        seasonTarget.put(Season.AUTUMN,  readBiome("biome_spoof.seasons.AUTUMN",  Biome.WINDSWEPT_SAVANNA));
        seasonTarget.put(Season.WINTER,  readBiome("biome_spoof.seasons.WINTER",  Biome.SNOWY_PLAINS));

        /* =================== OCEANS CONFIG =================== */
        this.oceansEnabled = plugin.cfg.climate.getBoolean("biome_spoof.oceans.enabled", true);
        this.oceansAffectRivers = plugin.cfg.climate.getBoolean("biome_spoof.oceans.affect_rivers", true);
        this.oceansAffectShores = plugin.cfg.climate.getBoolean("biome_spoof.oceans.affect_shores", true); // NUEVO
        this.oceansKeepDeepVariants = plugin.cfg.climate.getBoolean("biome_spoof.oceans.keep_deep_variants", true);
        this.prioritizeView = plugin.cfg.climate.getBoolean("biome_spoof.prioritize_view", false);

        oceanTarget.put(Season.SPRING, readBiome("biome_spoof.oceans.seasons.SPRING", Biome.LUKEWARM_OCEAN));
        oceanTarget.put(Season.SUMMER, readBiome("biome_spoof.oceans.seasons.SUMMER", Biome.WARM_OCEAN));
        oceanTarget.put(Season.AUTUMN, readBiome("biome_spoof.oceans.seasons.AUTUMN", Biome.OCEAN));
        oceanTarget.put(Season.WINTER, readBiome("biome_spoof.oceans.seasons.WINTER", Biome.FROZEN_OCEAN));

        /* =================== RIVERS CONFIG =================== */
        this.riversEnabled = plugin.cfg.climate.getBoolean("biome_spoof.rivers.enabled", oceansAffectRivers);

        riverTarget.put(Season.SPRING, readBiome("biome_spoof.rivers.seasons.SPRING", Biome.RIVER));
        riverTarget.put(Season.SUMMER, readBiome("biome_spoof.rivers.seasons.SUMMER", Biome.RIVER));
        riverTarget.put(Season.AUTUMN, readBiome("biome_spoof.rivers.seasons.AUTUMN", Biome.RIVER));
        riverTarget.put(Season.WINTER, readBiome("biome_spoof.rivers.seasons.WINTER", Biome.FROZEN_RIVER));
        /* ====================================================== */

        familyCache.clear();
        originalBiomeCache.clear();
        lastAppliedTarget.clear();
        pendingChunks.clear();
        pendingKeys.clear();
        lastPlayerChunk.clear();
    }

    private Biome readBiome(String path, Biome def) {
        String s = plugin.cfg.climate.getString(path, def.name());
        try {
            return Biome.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("[BiomeSpoof] Invalid biome '" + s + "' at " + path + ", using " + def);
            return def;
        }
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (task != null) task.cancel();
        // cada 10 ticks (~500 ms) es suficiente para un efecto suave
        this.task = plugin.getScheduler().runTimer(this, 40L, tickPeriod);
    }

    public void unregister() {
        if (task != null) task.cancel();
        HandlerList.unregisterAll(this);
        // al desregistrar, devolvemos el mundo a sus biomas originales
        revertAll();
        nudgeQueue.clear();
        nudgeLast.clear();
        COLD_CHUNKS.clear();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        long k = key(e.getChunk());
        // al cargar un chunk nuevo no queremos residuos marcados como spoofed
        spoofed.remove(k);
        // backups se mantienen si el chunk fue modificado; se limpia en onChunkUnload
        enqueueChunk(e.getChunk().getWorld(), e.getChunk().getX(), e.getChunk().getZ());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        Chunk ch = e.getChunk();
        long k = key(ch);
        // antes de soltar el chunk, lo devolvemos a su bioma original, si lo teníamos spoofeado
        if (spoofed.remove(k)) {
            revertChunk(ch);
        }
        backups.remove(k);
        familyCache.remove(k);
        originalBiomeCache.remove(k);
        lastAppliedTarget.remove(k);
        pendingKeys.remove(k);

        for (java.util.concurrent.ConcurrentLinkedDeque<Long> q : nudgeQueue.values()) {
            q.remove(k);
        }
    }

    @EventHandler
    public void onSeasonChange(SeasonUpdateEvent e) {
        if (mode == Mode.OFF) return;

        // activamos "modo transición" durante unos segundos: más presupuesto de pintado
        seasonTransitionUntil = System.currentTimeMillis() + TRANSITION_WINDOW_MS;

        // IMPORTANTE: ya NO hacemos revertAll aquí para evitar el barrido global.
        if (revertOnSeasonChange) {
//            plugin.getLogger().info("[BiomeSpoof] Season changed: using smooth repaint (no global revert).");
        }

        enqueueAroundOnlinePlayers(eventRadiusChunks);
    }

    @Override
    public void run() {
        if (mode == Mode.OFF) return;

        CalendarState st = seasons.getStateCopy();
        Season season = st.season;

        // Día dentro de la estación (1..DAYS_PER_SEASON) y factor de pre-transición (0..1)
        int dayInSeason = computeDayInSeason(st);
        double preTransitionFactor = computePreTransitionFactor(dayInSeason);

        // Estación actual y la siguiente → biomas configurados
        Season nextSeason = nextSeason(season);
        final Biome currentTarget = seasonTarget.getOrDefault(season, Biome.PLAINS);
        final Biome nextTarget    = seasonTarget.getOrDefault(nextSeason, currentTarget);

        // Targets de océano (si están habilitados)
        final Biome currentOceanTarget = oceanTarget.getOrDefault(season, Biome.OCEAN);
        final Biome nextOceanTarget    = oceanTarget.getOrDefault(nextSeason, currentOceanTarget);

        long now = System.currentTimeMillis();
        int effectiveBudget = budgetPerTick;

        // Durante la ventana de transición, el presupuesto se escala de forma suave
        if (now < seasonTransitionUntil) {
            double extra = 1.0 + (preTransitionFactor * (TRANSITION_BUDGET_MULTIPLIER - 1));
            if (extra < 1.0) extra = 1.0;
            effectiveBudget = (int) Math.max(1, Math.round(budgetPerTick * extra));
        }

        int budget = effectiveBudget;
        if (budget <= 0) return;

        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(budget);
        Map<UUID, Player> worldPlayers = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            worldPlayers.putIfAbsent(p.getWorld().getUID(), p);
        }

        int processed = 0;
        Set<Long> processedThisTick = java.util.concurrent.ConcurrentHashMap.newKeySet();
        while (remaining.get() > 0) {
            ChunkRef ref = pendingChunks.poll();
            if (ref == null) break;
            pendingKeys.remove(ref.key);
            processed++;

            World w = Bukkit.getWorld(ref.worldId);
            if (w == null) continue;
            if (!w.isChunkLoaded(ref.cx, ref.cz)) continue;

            Player p = worldPlayers.get(ref.worldId);
            scheduleChunkSpoof(p, w, ref.cx, ref.cz,
                    currentTarget, nextTarget,
                    currentOceanTarget, nextOceanTarget,
                    preTransitionFactor,
                    remaining,
                    processedThisTick);
        }

        if (processed > 0) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                plugin.getScheduler().runAtEntity(p, task -> flushNudgesForPlayer(p));
            }
        }
    }

    private void scheduleChunkSpoof(Player p, World w, int cx, int cz,
                                    Biome currentTarget, Biome nextTarget,
                                    Biome currentOceanTarget, Biome nextOceanTarget,
                                    double preTransitionFactor,
                                    java.util.concurrent.atomic.AtomicInteger remaining,
                                    Set<Long> processedThisTick) {
        long ck = key(w, cx, cz);
        if (!processedThisTick.add(ck)) return;

        Location chunkLoc = new Location(w, (cx << 4) + 8, w.getMinHeight(), (cz << 4) + 8);
        plugin.getScheduler().runAtLocation(chunkLoc, task -> {
            if (remaining.get() <= 0) return;
            if (!w.isChunkLoaded(cx, cz)) return;

            Chunk ch = w.getChunkAt(cx, cz);
            Family fam = classifyOriginalFamily(ch);

            Biome chunkTarget = chooseTargetBiomeForChunk(
                    ck, fam,
                    currentTarget, nextTarget,
                    currentOceanTarget, nextOceanTarget,
                    preTransitionFactor,
                    ch
            );

            // Fuera de invierno, no tocamos chunks fríos de origen
            if (shouldSkipSpoofForChunk(ch)) {
                return;
            }

            Biome lastTarget = lastAppliedTarget.get(ck);
            if (chunkTarget.equals(lastTarget)) {
                return; // ya está al bioma objetivo según cache, no tocamos
            }

            Biome[] old = captureAndApply(ch, chunkTarget);
            // solo guardamos backup la PRIMERA vez que tocamos este chunk
            if (old != null && !backups.containsKey(ck)) {
                backups.put(ck, old);
            }

            spoofed.add(ck);
            if (p != null) {
                enqueueNudge(p, w, cx, cz);
            }
            remaining.decrementAndGet();
        });
    }

    /* ===== helpers ===== */

    private List<Offset> getBaseOffsets(int radius) {
        return offsetCache.computeIfAbsent(radius, r -> {
            List<Offset> list = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int dist = Math.max(Math.abs(dx), Math.abs(dz));
                    double len = Math.sqrt((double) dx * dx + (double) dz * dz);
                    double invLen = (len > 1e-4) ? (1.0 / len) : 0.0;
                    list.add(new Offset(dx, dz, dist, invLen));
                }
            }
            list.sort(Comparator.comparingInt(o -> o.dist));
            return list;
        });
    }

    private Biome getRepresentativeOriginalOceanBiome(Chunk ch) {
        long k = key(ch);
        Biome[] old = backups.get(k);
        if (old != null && old.length > 0) {
            for (Biome b : old) {
                if (isOceanBiome(b)) return b;
            }
            // si no hay ocean en backup, devolvemos el primero
            return old[0];
        }
        return getRepresentativeOriginalBiome(ch);
    }


    private Biome chooseTargetFor(Season season, Biome original) {
        // Bioma global para esa estación según climate.yml
        Biome global = seasonTarget.get(season);
        if (global == null) {
            return original; // por seguridad, si falta en config
        }
        return global;       // aplica el bioma de estación a cualquier LAND
    }

    /** Consideramos "frío" cualquier bioma con nieve/hielo en el nombre, montañas, etc. */
    private boolean isColdBiome(Biome biome) {
        // Cherry Grove NO es un bioma frío real, no lo tratamos como nival
        if (biome == Biome.CHERRY_GROVE) {
            return false;
        }

        String name = biome.name();
        return name.contains("SNOW")
                || name.contains("FROZEN")
                || name.contains("ICE")
                || name.equals("GROVE")
                || name.contains("SNOWY_TAIGA")
                || name.contains("PEAK")
                || name.contains("MOUNTAIN");
    }

    private boolean isOceanBiome(Biome b) {
        return b.name().contains("OCEAN");
    }

    private boolean isRiverBiome(Biome b) {
        return b.name().contains("RIVER");
    }

    private boolean isDeepOcean(Biome b) {
        return b.name().contains("DEEP_") && isOceanBiome(b);
    }

    private boolean isShoreBiome(Biome b) {
        String n = b.name();
        return n.contains("BEACH") || n.contains("SHORE");
    }

    /**
     * Clasifica el chunk por familia usando SIEMPRE lo original:
     * - Si ya tenemos backup → usamos eso.
     * - Si no → muestreo baratito del bioma actual (aún original).
     *
     * Reglas:
     *  - OCEAN: biomas OCEAN o (si affect_shores) BEACH/SHORE
     *  - RIVER: biomas RIVER (si rivers.enabled)
     *  - LAND: resto
     */
    private Family classifyOriginalFamily(Chunk ch) {
        long k = key(ch);

        Family cached = familyCache.get(k);
        if (cached != null) {
            return cached;
        }

        Biome[] old = backups.get(k);
        if (old != null && old.length > 0) {
            for (int i = 0; i < old.length; i++) {
                Biome b = old[i];
                if (oceansEnabled) {
                    if (isOceanBiome(b)) {
                        familyCache.put(k, Family.OCEAN);
                        return Family.OCEAN;
                    }
                    if (oceansAffectShores && isShoreBiome(b)) {
                        familyCache.put(k, Family.OCEAN);
                        return Family.OCEAN;
                    }
                }
                if (riversEnabled && isRiverBiome(b)) {
                    familyCache.put(k, Family.RIVER);
                    return Family.RIVER;
                }
            }
            familyCache.put(k, Family.LAND);
            return Family.LAND;
        }

        World w = ch.getWorld();
        int bx = ch.getX() << 4;
        int bz = ch.getZ() << 4;
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 1;
        int sampleY = w.getSeaLevel();
        if (sampleY < minY) sampleY = minY;
        if (sampleY > maxY) sampleY = maxY;

        for (int x = 0; x < 16; x += 8) {
            for (int z = 0; z < 16; z += 8) {
                Biome b = w.getBiome(bx + x, sampleY, bz + z);
                if (oceansEnabled) {
                    if (isOceanBiome(b)) {
                        familyCache.put(k, Family.OCEAN);
                        return Family.OCEAN;
                    }
                    if (oceansAffectShores && isShoreBiome(b)) {
                        familyCache.put(k, Family.OCEAN);
                        return Family.OCEAN;
                    }
                }
                if (riversEnabled && isRiverBiome(b)) {
                    familyCache.put(k, Family.RIVER);
                    return Family.RIVER;
                }
            }
        }

        familyCache.put(k, Family.LAND);
        return Family.LAND;
    }


    /**
     * Intenta obtener un bioma original representativo del chunk
     * (backup si existe, si no muestreo).
     */
    private Biome getRepresentativeOriginalBiome(Chunk ch) {
        long k = key(ch);
        Biome cached = originalBiomeCache.get(k);
        if (cached != null) {
            return cached;
        }

        Biome[] old = backups.get(k);
        if (old != null && old.length > 0) {
            Biome b = old[0];
            originalBiomeCache.put(k, b);
            return b;
        }

        World w = ch.getWorld();
        int bx = ch.getX() << 4;
        int bz = ch.getZ() << 4;
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 1;
        int sampleY = w.getSeaLevel();
        if (sampleY < minY) sampleY = minY;
        if (sampleY > maxY) sampleY = maxY;
        Biome b = w.getBiome(bx + 8, sampleY, bz + 8);
        originalBiomeCache.put(k, b);
        return b;
    }

    /**
     * Ajusta el target de océano para respetar deep/shallow según el original.
     */
    private Biome applyOceanVariant(Biome baseTarget, Biome originalOcean) {
        if (!oceansKeepDeepVariants) return baseTarget;

        boolean origDeep = isDeepOcean(originalOcean);
        String baseName = baseTarget.name();

        if (origDeep) {
            // si ya es deep, ok
            if (baseName.startsWith("DEEP_")) return baseTarget;

            // intentar DEEP_<base>
            try {
                return Biome.valueOf("DEEP_" + baseName);
            } catch (IllegalArgumentException ignored) {
                // no existe deep para ese biome → dejamos base
                return baseTarget;
            }
        } else {
            // original shallow: si el base viene deep por config, intentamos bajarlo
            if (baseName.startsWith("DEEP_")) {
                String shallow = baseName.substring("DEEP_".length());
                try {
                    return Biome.valueOf(shallow);
                } catch (IllegalArgumentException ignored) {
                    return baseTarget;
                }
            }
        }

        return baseTarget;
    }

    /**
     * Elige el bioma objetivo para un chunk dado, considerando familia:
     * - OCEAN/RIVER (si enabled) usa oceanTarget.
     * - LAND usa tu lógica normal (autumn taiga/birch, etc).
     * Además respeta preTransition con ruido determinista.
     */
    private Biome chooseTargetBiomeForChunk(
            long chunkKey,
            Family family,
            Biome currentLandTarget,
            Biome nextLandTarget,
            Biome currentOceanTarget,
            Biome nextOceanTarget,
            double preTransitionFactor,
            Chunk ch
    ) {
        // RÍOS con target propio
        if (family == Family.RIVER && riversEnabled) {
            Biome curR = riverTarget.getOrDefault(seasons.getStateCopy().season, Biome.RIVER);
            Biome nextR = riverTarget.getOrDefault(nextSeason(seasons.getStateCopy().season), curR);
            return chooseTargetBiomeForChunk(chunkKey, curR, nextR, preTransitionFactor);
        }

        // OCÉANOS + ORILLAS siguen oceanTarget
        if (family == Family.OCEAN && oceansEnabled) {
            Biome base = chooseTargetBiomeForChunk(chunkKey, currentOceanTarget, nextOceanTarget, preTransitionFactor);

            // conservar deep/shallow solo si el original era océano
            Biome origOcean = getRepresentativeOriginalOceanBiome(ch);
            if (isOceanBiome(origOcean)) {
                return applyOceanVariant(base, origOcean);
            }
            return base;
        }

        // LAND → tu comportamiento normal
        Biome orig = getRepresentativeOriginalBiome(ch);
        Season sNow = seasons.getStateCopy().season;
        Season sNext = nextSeason(sNow);

        Biome landCur = chooseTargetFor(sNow, orig);
        Biome landNext = chooseTargetFor(sNext, orig);

        if (landCur == orig && landNext == orig) return orig;
        if (landCur == landNext) return landCur;

        return chooseTargetBiomeForChunk(chunkKey, landCur, landNext, preTransitionFactor);
    }

    /**
     * Aplica el bioma objetivo en una rejilla 4x4x4 dentro del chunk.
     * - Si es la primera vez que tocamos este chunk: captura los biomas previos y los devuelve.
     * - Siempre que se aplica, hace un refreshChunk para que el cliente reciba el chunk
     *   y vea el nuevo color sin reconectar.
     */
    private Biome[] captureAndApply(Chunk ch, Biome target) {
        try {
            World w = ch.getWorld();
            int bx = ch.getX() << 4;
            int bz = ch.getZ() << 4;
            int minY = w.getMinHeight();
            int maxY = w.getMaxHeight();

            long k = key(ch);
            Biome[] existing = backups.get(k);
            List<Biome> prevs = (existing == null) ? new ArrayList<>() : null;

            boolean anyChange = false;

            for (int x = 0; x < 16; x += STEP_XZ) {
                for (int z = 0; z < 16; z += STEP_XZ) {
                    for (int y = minY; y < maxY; y += STEP_Y) {
                        int wx = bx + x;
                        int wz = bz + z;
                        int wy = y;

                        Biome current = w.getBiome(wx, wy, wz);

                        if (prevs != null) {
                            prevs.add(current);
                        }

                        if (current != target) {
                            anyChange = true;
                            w.setBiome(wx, wy, wz, target);
                        }
                    }
                }
            }

            if (!anyChange) {
                // Nada que pintar; cacheamos el objetivo para evitar reintentos.
                lastAppliedTarget.put(k, target);
                return null;
            }

            if (prevs != null) {
                boolean cold = false;
                for (Biome b : prevs) {
                    if (isColdBiome(b)) {
                        cold = true;
                        break;
                    }
                }
                if (cold) {
                    COLD_CHUNKS.add(k);
                }
            }

            // solo refrescamos si hubo cambios
            w.refreshChunk(ch.getX(), ch.getZ());

            lastAppliedTarget.put(k, target);

            if (prevs != null) {
                Biome[] arr = prevs.toArray(new Biome[0]);

                // ✅ NUEVO: guardar en disco la PRIMERA vez que tocamos este chunk
                diskBackups.saveFirstTouch(ch, arr, STEP_XZ, STEP_Y);

                return arr;
            } else {
                return null;
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[BiomeSpoof] spoof error " + ch.getX() + "," + ch.getZ() + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * Revierte un chunk a sus biomas originales usando el backup.
     */
    private void revertChunk(Chunk ch) {
        Biome[] old = backups.get(key(ch));
        if (old == null) return;

        try {
            World w = ch.getWorld();
            int bx = ch.getX() << 4;
            int bz = ch.getZ() << 4;
            int minY = w.getMinHeight();
            int maxY = w.getMaxHeight();

            int i = 0;
            for (int x = 0; x < 16; x += 4) {
                for (int z = 0; z < 16; z += 4) {
                    for (int y = minY; y < maxY; y += 4) {
                        if (i >= old.length) break;
                        w.setBiome(bx + x, y, bz + z, old[i++]);
                    }
                }
            }

            // refrescamos para que el cliente vuelva a ver el bioma original
            w.refreshChunk(ch.getX(), ch.getZ());

            // pequeño nudge adicional (no es estrictamente necesario, pero ayuda en algunos casos)
            nudgeViewers(w, ch.getX(), ch.getZ());
            lastAppliedTarget.remove(key(ch));
        } catch (Throwable t) {
            plugin.getLogger().warning("[BiomeSpoof] revert error " + ch.getX() + "," + ch.getZ() + ": " + t.getMessage());
        }
    }

    private long key(Chunk ch) {
        return key(ch.getWorld(), ch.getX(), ch.getZ());
    }

    private long key(World w, int cx, int cz) {
        long k = (((long) cx) & 0xffffffffL) << 32 | (((long) cz) & 0xffffffffL);
        // incluimos el UUID del mundo para evitar colisiones entre mundos
        return k ^ (w.getUID().getMostSignificantBits() ^ w.getUID().getLeastSignificantBits());
    }

    /** Consulta global: ¿este chunk era originalmente frío/nival? */
    public static boolean isChunkNaturallySnowy(World w, int cx, int cz) {
        long k = (((long) cx) & 0xffffffffL) << 32 | (((long) cz) & 0xffffffffL);
        k = k ^ (w.getUID().getMostSignificantBits() ^ w.getUID().getLeastSignificantBits());
        return COLD_CHUNKS.contains(k);
    }

    private void revertAll() {
        for (World w : Bukkit.getWorlds()) {
            for (Chunk ch : w.getLoadedChunks()) {
                long k = key(ch);
                if (spoofed.contains(k)) {
                    revertChunk(ch);
                }
            }
        }
        spoofed.clear();
        backups.clear();
        nudgeQueue.clear();
        nudgeLast.clear();
        lastAppliedTarget.clear();
        pendingChunks.clear();
        pendingKeys.clear();
        lastPlayerChunk.clear();
    }

    /**
     * Devuelve true si este chunk es naturalmente frío/nival.
     * Marca el chunk en COLD_CHUNKS la primera vez.
     */
    private boolean shouldSkipSpoofForChunk(Chunk ch) {
        long k = key(ch);

        // Ya clasificado como frío de origen
        if (COLD_CHUNKS.contains(k)) {
            return true;
        }

        // Ya hemos hecho captureAndApply al menos una vez → si fuera frío de origen
        // lo habríamos marcado en COLD_CHUNKS dentro de captureAndApply.
        // No volvemos a muestrear para no confundir bioma original con bioma pintado.
        if (backups.containsKey(k)) {
            return false;
        }

        // Chunk "nuevo": todavía tiene sus biomas originales,
        // así que podemos decidir si es frío de origen.
        World w = ch.getWorld();
        int bx = ch.getX() << 4;
        int bz = ch.getZ() << 4;
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();

        for (int x = 0; x < 16; x += 4) {
            for (int z = 0; z < 16; z += 4) {
                for (int y = minY; y < maxY; y += 32) {
                    Biome b = w.getBiome(bx + x, y, bz + z);
                    if (isColdBiome(b)) {
                        COLD_CHUNKS.add(k); // frío de origen
                        return true;        // NO lo pintamos nunca
                    }
                }
            }
        }

        // No es frío de origen → se puede spoofear normal
        return false;
    }

    /* ===================== NUDGE (anti-flicker) ===================== */

    private void nudgeViewers(World w, int cx, int cz) {
        for (Player viewer : w.getPlayers()) {
            int vcx = viewer.getLocation().getBlockX() >> 4;
            int vcz = viewer.getLocation().getBlockZ() >> 4;
            int view = Bukkit.getViewDistance() + 1;
            if (Math.abs(vcx - cx) <= view && Math.abs(vcz - cz) <= view) {
                enqueueNudge(viewer, w, cx, cz);
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        enqueueAroundPlayer(p, eventRadiusChunks);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        Location from = e.getFrom();
        if (to == null) return;

        int fromCx = from.getBlockX() >> 4;
        int fromCz = from.getBlockZ() >> 4;
        int toCx = to.getBlockX() >> 4;
        int toCz = to.getBlockZ() >> 4;
        if (fromCx == toCx && fromCz == toCz) return;

        Player p = e.getPlayer();
        long k = key(p.getWorld(), toCx, toCz);
        Long last = lastPlayerChunk.put(p.getUniqueId(), k);
        if (last != null && last == k) return;

        enqueueAroundPlayer(p, eventRadiusChunks);
    }

    private void enqueueAroundOnlinePlayers(int radius) {
        if (radius <= 0) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            enqueueAroundPlayer(p, radius);
        }
    }

    private void enqueueAroundPlayer(Player p, int radius) {
        if (p == null || !p.isOnline()) return;
        World w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return;

        Location loc = p.getLocation();
        int pcx = loc.getBlockX() >> 4;
        int pcz = loc.getBlockZ() >> 4;

        enqueueChunk(w, pcx, pcz);
        if (radius <= 0) return;

        List<Offset> offsets = getBaseOffsets(radius);
        if (prioritizeView) {
            offsets = new ArrayList<>(offsets);
            Vector look = loc.getDirection().clone();
            look.setY(0);
            if (look.lengthSquared() < 1e-4) {
                look = new Vector(0, 0, 1);
            } else {
                look.normalize();
            }
            final double lookX = look.getX();
            final double lookZ = look.getZ();
            offsets.sort(Comparator
                    .comparingInt((Offset o) -> o.dist)
                    .thenComparingDouble(o -> -((o.dx * lookX + o.dz * lookZ) * o.invLen)));
        }

        for (Offset off : offsets) {
            int cx = pcx + off.dx;
            int cz = pcz + off.dz;
            enqueueChunk(w, cx, cz);
        }
    }

    private void enqueueChunk(World w, int cx, int cz) {
        long k = key(w, cx, cz);
        if (!pendingKeys.add(k)) return;
        pendingChunks.add(new ChunkRef(w.getUID(), cx, cz, k));
    }

    private void enqueueNudge(Player p, World w, int cx, int cz) {
        if (plugin.getFoliaLib().isFolia()) {
            return;
        }
        long ck = key(w, cx, cz);
        String cooldownKey = p.getUniqueId() + ":" + ck;
        long now = System.currentTimeMillis();
        Long last = nudgeLast.get(cooldownKey);
        if (last != null && (now - last) < NUDGE_COOLDOWN_MS) return;
        nudgeLast.put(cooldownKey, now);

        nudgeQueue.computeIfAbsent(p.getUniqueId(), id -> new java.util.concurrent.ConcurrentLinkedDeque<>()).add(ck);
    }

    private void flushNudgesForPlayer(Player p) {
        java.util.concurrent.ConcurrentLinkedDeque<Long> q = nudgeQueue.get(p.getUniqueId());
        if (q == null || q.isEmpty()) return;

        int sent = 0;
        while (sent < NUDGES_PER_TICK && !q.isEmpty()) {
            long ck = q.poll();
            sent++;

            World w = p.getWorld();
            int baseX = p.getLocation().getBlockX() >> 4;
            int baseZ = p.getLocation().getBlockZ() >> 4;
            int view = Bukkit.getViewDistance() + 2;

            boolean done = false;
            for (int dx = -view; dx <= view && !done; dx++) {
                for (int dz = -view; dz <= view && !done; dz++) {
                    int cx = baseX + dx;
                    int cz = baseZ + dz;
                    if (!w.isChunkLoaded(cx, cz)) continue;
                    if (key(w, cx, cz) != ck) continue;

                    int minY = w.getMinHeight();
                    int bx = (cx << 4);
                    int bz = (cz << 4);
                    Location loc = new Location(w, bx, minY, bz);

                    BlockData fake = NUDGE_FAKE.createBlockData();
                    BlockData real = w.getBlockAt(loc).getBlockData();

                    p.sendBlockChange(loc, fake);
                    plugin.getScheduler().runNextTick(task -> p.sendBlockChange(loc, real));

                    done = true;
                }
            }
        }

        if (q.isEmpty()) {
            nudgeQueue.remove(p.getUniqueId());
        }
    }

    /* ===================== NUEVOS HELPERS DE TRANSICIÓN ===================== */

    /**
     * En tu CalendarState, st.day ya es el día dentro de la estación.
     * Aquí sólo lo recortamos al rango 1..daysPerSeason actual.
     */
    private int computeDayInSeason(CalendarState st) {
        int d = st.day;
        int max = Math.max(1, seasons.getDaysPerSeason());
        if (d < 1) d = 1;
        if (d > max) d = max;
        return d;
    }

    /**
     * Calcula el factor de pre-transición (0..1) usando SIEMPRE
     * los últimos PRE_TRANSITION_DAYS días de la estación.
     *
     * Ejemplos:
     *  - daysPerSeason = 28 → usa días 26,27,28
     *  - daysPerSeason = 15 → usa días 13,14,15
     *  - daysPerSeason = 10 → usa días 8,9,10
     */
    private double computePreTransitionFactor(int dayInSeason) {
        int daysPerSeason = Math.max(1, seasons.getDaysPerSeason());
        int window = Math.min(PRE_TRANSITION_DAYS, daysPerSeason);

        int start = daysPerSeason - window + 1; // primer día de transición
        if (dayInSeason < start) {
            return 0.0; // todavía lejos del final
        }

        int step = dayInSeason - start; // 0..window-1
        double factor = (step + 1) / (double) window;
        if (factor < 0.0) factor = 0.0;
        if (factor > 1.0) factor = 1.0;
        return factor;
    }


    private Season nextSeason(Season s) {
        return switch (s) {
            case SPRING -> Season.SUMMER;
            case SUMMER -> Season.AUTUMN;
            case AUTUMN -> Season.WINTER;
            case WINTER -> Season.SPRING;
        };
    }

    public Biome getOriginalBiomeApprox(World w, int x, int y, int z) {
        Chunk ch = w.getChunkAt(x >> 4, z >> 4);
        Biome[] old = backups.get(key(ch));

        if (old == null || old.length == 0) {
            return w.getBiome(x, y, z);
        }

        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();
        int yy = Math.max(minY, Math.min(maxY - 1, y));

        int xCount = 16 / STEP_XZ; // 4
        int zCount = 16 / STEP_XZ; // 4
        int yCount = (maxY - minY) / STEP_Y;

        int lx = (x & 15) / STEP_XZ;
        int lz = (z & 15) / STEP_XZ;
        int ly = (yy - minY) / STEP_Y;

        int idx = ((lx * zCount) + lz) * yCount + ly;
        if (idx < 0 || idx >= old.length) return old[0];
        return old[idx];
    }

    public Biome getOriginalBiomeApproxOrNull(World w, int x, int y, int z) {
        Chunk ch = w.getChunkAt(x >> 4, z >> 4);
        Biome[] old = backups.get(key(ch));
        if (old == null || old.length == 0) return null; // <- clave

        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();
        int yy = Math.max(minY, Math.min(maxY - 1, y));

        int xCount = 16 / STEP_XZ;
        int zCount = 16 / STEP_XZ;
        int yCount = (maxY - minY) / STEP_Y;

        int lx = (x & 15) / STEP_XZ;
        int lz = (z & 15) / STEP_XZ;
        int ly = (yy - minY) / STEP_Y;

        int idx = ((lx * zCount) + lz) * yCount + ly;
        if (idx < 0 || idx >= old.length) return old[0];
        return old[idx];
    }


    private Biome chooseTargetBiomeForChunk(
            long chunkKey,
            Biome currentTarget,
            Biome nextTarget,
            double preTransitionFactor
    ) {
        if (nextTarget == null || currentTarget == nextTarget || preTransitionFactor <= 0.0) {
            return currentTarget;
        }
        if (preTransitionFactor >= 1.0) {
            return nextTarget;
        }

        // Ruido determinista simple basado en la key del chunk
        long h = chunkKey * 1103515245L + 12345L;
        h ^= (h >>> 16);
        int bucket = (int) (h & 0xFFFF); // 0..65535
        double threshold = preTransitionFactor * 65536.0;

        if (bucket < threshold) {
            return nextTarget;
        }
        return currentTarget;
    }

    public synchronized void setEnabled(boolean enabled) {
        if (enabled) {
            mode = Mode.GLOBAL_RING;
            if (task == null || task.isCancelled()) {
                task = plugin.getScheduler().runTimer(this, 40L, tickPeriod);
            }
        } else {
            mode = Mode.OFF;
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
    }

    public boolean isEnabled() {
        return mode != Mode.OFF;
    }

    public Kinkin.aeternum.world.BiomeBackupStore getDiskBackups() {
        return diskBackups;
    }

}
