package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.util.Vector;
import org.bukkit.Location;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AutumnSoilPainter implements Runnable, Listener {

    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;

    private WrappedTask task;
    private final Random random = new Random();

    // cuántos CHUNKS intentamos procesar por tick (global)
    private int chunksPerTick;

    // radio en chunks alrededor del jugador
    private int radiusChunks;

    // periodo del task
    private long tickPeriod;

    // probabilidad base de recolorear cada bloque de hojas
    private double leafChancePerBlock;
    private boolean prioritizeView;
    private boolean cleanupEnabled;
    private boolean fixResidualEnabled;
    private int cleanupMaxRevertPerChunk;

    // tipos de hojas que vamos a transformar
    private final Set<Material> leafTypes = EnumSet.of(
            Material.SPRUCE_LEAVES,
            Material.BIRCH_LEAVES,
            Material.CHERRY_LEAVES
    );

    // hojas pintadas -> para revertir luego
    // key = worldUUID:x:y:z, value = tipo original
    private final Map<String, Material> paintedLeaves = new ConcurrentHashMap<>();

    // para ordenar offsets como BiomeSpoofAdapter
    private static final class Offset {
        final int dx, dz;
        final int dist;
        final double invLen;
        Offset(int dx, int dz, int dist, double invLen) {
            this.dx = dx;
            this.dz = dz;
            this.dist = dist;
            this.invLen = invLen;
        }
    }

    // calendario
    private static final int DAYS_PER_SEASON       = 28; // tu calendario
    private static final int PRE_AUTUMN_START_DAY  = 26; // 26,27,28 de verano
    private static final int PRE_AUTUMN_RAMP_DAYS  = 3;  // 3 días de rampa

    // flip para alternar el patrón de columnas (anti-parches)
    private boolean gridFlip = false;
    private final Map<Integer, List<Offset>> offsetCache = new ConcurrentHashMap<>();

    public AutumnSoilPainter(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        this.plugin = plugin;
        this.seasons = seasons;
        reloadFromConfig();
    }

    public void reloadFromConfig() {
        var y = plugin.cfg.climate;

        this.chunksPerTick = Math.max(
                2,
                y.getInt("autumn_soil.attempts_per_tick", 4)
        );

        this.radiusChunks = Math.max(
                2,
                y.getInt("autumn_soil.radius_chunks", 4)
        );

        this.tickPeriod = Math.max(5L, y.getLong("autumn_soil.tick_period_ticks", 10L));

        this.leafChancePerBlock = y.getDouble("autumn_soil.leaf_chance_per_block", 1.0);
        if (this.leafChancePerBlock < 0.0) this.leafChancePerBlock = 0.0;
        if (this.leafChancePerBlock > 1.0) this.leafChancePerBlock = 1.0;
        this.prioritizeView = y.getBoolean("autumn_soil.prioritize_view", false);
        this.cleanupEnabled = y.getBoolean("autumn_soil.cleanup_enabled", false);
        this.fixResidualEnabled = y.getBoolean("autumn_soil.fix_residual_enabled", false);
        this.cleanupMaxRevertPerChunk = Math.max(0, y.getInt("autumn_soil.cleanup_max_revert_per_chunk", 16));
    }

    public void register() {
        if (task != null) task.cancel();
        if (!plugin.cfg.climate.getBoolean("autumn_soil.enabled", false)) return;
        this.task = plugin.getScheduler().runTimer(this, 60L, tickPeriod);

        // registrar como listener
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void unregister() {
        if (task != null) task.cancel();
        task = null;
    }

    @Override
    public void run() {
        CalendarState st = seasons.getStateCopy();
        gridFlip = !gridFlip; // alternamos patrón cada tick
        boolean localGridFlip = gridFlip;

        int totalBudget = Math.max(1, chunksPerTick);
        int online = Bukkit.getOnlinePlayers().size();
        if (online <= 0) return;

        int baseBudget = Math.max(1, totalBudget / online);
        int extra = totalBudget - (baseBudget * online);

        for (Player p : Bukkit.getOnlinePlayers()) {
            int budget = baseBudget + (extra-- > 0 ? 1 : 0);
            plugin.getScheduler().runAtEntity(p, task -> tickForPlayer(p, st, localGridFlip, budget));
        }
    }

    private void tickForPlayer(Player p, CalendarState st, boolean localGridFlip, int budget) {
        if (!p.isOnline() || budget <= 0) return;

        Season season = st.season;
        int dayInSeason = computeDayInSeason(st);

        // factor 0..1 de "cuánto queremos pintar" este tick
        double paintFactor = 0.0;

        if (season == Season.AUTUMN) {
            // en otoño queremos árboles completamente amarillos
            paintFactor = 1.0;
        } else if (season == Season.SUMMER) {
            // pre-otoño: verano días 26–28
            paintFactor = computePreAutumnFactor(dayInSeason);
        }

        World w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return;

        Location loc = p.getLocation();
        int pcx = loc.getBlockX() >> 4;
        int pcz = loc.getBlockZ() >> 4;

        // fuera de pre-otoño y otoño -> MODO LIMPIEZA + AUTOREPARACIÓN
        if (paintFactor <= 0.0) {
            if (cleanupEnabled) {
                scheduleCleanupAroundPlayer(p, w, pcx, pcz, budget);
            }
            return;
        }

        // "otoño maduro": desde día 3 queremos que cerca del jugador estén FULL pintados
        boolean matureAutumn = (season == Season.AUTUMN && dayInSeason >= 3);

        int radius = radiusChunks;

        List<Offset> offsets = getBaseOffsets(radius);
        if (prioritizeView) {
            Vector look = loc.getDirection().clone();
            look.setY(0);
            if (look.lengthSquared() < 1e-4) {
                look = new Vector(0, 0, 1);
            } else {
                look.normalize();
            }
            final double lookX = look.getX();
            final double lookZ = look.getZ();
            offsets = new ArrayList<>(offsets);
            offsets.sort(java.util.Comparator
                    .comparingInt((Offset o) -> o.dist)
                    .thenComparingDouble(o -> -((o.dx * lookX + o.dz * lookZ) * o.invLen)));
        }

        // 1) siempre procesamos primero el chunk donde está el jugador
        if (budget > 0) {
            scheduleChunkPaint(w, pcx, pcz, true, paintFactor, matureAutumn, localGridFlip);
            budget--;
        }

        // 2) luego los chunks cercanos, priorizando delante
        for (Offset off : offsets) {
            if (budget <= 0) break;
            int cx = pcx + off.dx;
            int cz = pcz + off.dz;

            boolean highDetail = matureAutumn && off.dist <= 1; // anillo 1 también full cuando ya vamos en otoño
            scheduleChunkPaint(w, cx, cz, highDetail, paintFactor, matureAutumn, localGridFlip);
            budget--;
        }
    }

    private List<Offset> getBaseOffsets(int radius) {
        return offsetCache.computeIfAbsent(radius, r -> {
            List<Offset> list = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dz));
                    if (dist == 0) continue;
                    double len = Math.sqrt((double) dx * dx + (double) dz * dz);
                    double invLen = (len > 1e-4) ? (1.0 / len) : 0.0;
                    list.add(new Offset(dx, dz, dist, invLen));
                }
            }
            list.sort(Comparator.comparingInt(o -> o.dist));
            return list;
        });
    }

    private void scheduleChunkPaint(World w, int cx, int cz, boolean highDetail, double paintFactor,
                                    boolean matureAutumn, boolean localGridFlip) {
        Location chunkLoc = new Location(w, (cx << 4) + 8, w.getMinHeight(), (cz << 4) + 8);
        plugin.getScheduler().runAtLocation(chunkLoc, task -> {
            if (!w.isChunkLoaded(cx, cz)) return;
            processChunk(w, cx, cz, highDetail, paintFactor, matureAutumn, localGridFlip);
        });
    }

    private void scheduleCleanupAroundPlayer(Player p, World w, int pcx, int pcz, int budget) {
        int radius = radiusChunks;
        int maxRevertPerChunk = cleanupMaxRevertPerChunk;
        if (maxRevertPerChunk <= 0) return;

        for (int cx = pcx - radius; cx <= pcx + radius && budget > 0; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius && budget > 0; cz++) {
                int fcx = cx;
                int fcz = cz;
                Location chunkLoc = new Location(w, (fcx << 4) + 8, w.getMinHeight(), (fcz << 4) + 8);
                plugin.getScheduler().runAtLocation(chunkLoc, task -> {
                    if (!w.isChunkLoaded(fcx, fcz)) return;
                    revertSomeLeavesInChunk(w, fcx, fcz, maxRevertPerChunk);
                    if (fixResidualEnabled) {
                        fixChunkResidualLeaves(w, fcx, fcz);
                    }
                });
                budget--;
            }
        }
    }

    /**
     * Fuera de otoño: recorre chunks alrededor de los jugadores y corrige
     * hojas ACACIA_LEAVES que probablemente son spruce/birch pintadas
     * que quedaron bugueadas después de un reinicio/reinstalación.
     */
    private void fixChunkResidualLeaves(World w, int cx, int cz) {
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();

        int baseX = cx << 4;
        int baseZ = cz << 4;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;

                int yTop = w.getHighestBlockYAt(wx, wz) - 1;
                if (yTop < minY) continue;

                int scanMinY = Math.max(minY, yTop - 12);
                int scanMaxY = Math.min(maxY, yTop + 8);

                for (int y = scanMinY; y <= scanMaxY; y++) {
                    Block b = w.getBlockAt(wx, y, wz);

                    // Solo hojas de acacia “raras”
                    if (b.getType() != Material.ACACIA_LEAVES) continue;

                    Material guessed = guessOriginalFromWorld(b);
                    if (guessed == null || guessed == Material.ACACIA_LEAVES) continue;

                    BlockData current = b.getBlockData();
                    int distance = 1;
                    if (current instanceof Leaves leaves) {
                        distance = leaves.getDistance();
                    }

                    Leaves backLeaves = (Leaves) guessed.createBlockData();
                    backLeaves.setDistance(distance);
                    backLeaves.setPersistent(false); // comportamiento vanilla
                    b.setBlockData(backLeaves, false);
                }
            }
        }
    }

    /**
     * Intenta adivinar si una ACACIA_LEAVES en este sitio debería ser
     * SPRUCE_LEAVES o BIRCH_LEAVES, mirando troncos cercanos y bioma.
     */
    private Material guessOriginalFromWorld(Block leaf) {
        World w = leaf.getWorld();
        Biome biome = leaf.getBiome();

        // Nunca tocamos acacias reales en biomas de sabana
        if (biome == Biome.SAVANNA ||
                biome == Biome.SAVANNA_PLATEAU ||
                biome == Biome.WINDSWEPT_SAVANNA) {
            return null;
        }

        // Buscamos troncos cercanos
        int radius = 6;
        int lx = leaf.getX();
        int ly = leaf.getY();
        int lz = leaf.getZ();

        boolean nearSpruce = false;
        boolean nearBirch  = false;

        for (int x = lx - radius; x <= lx + radius; x++) {
            for (int y = ly - radius; y <= ly + radius; y++) {
                for (int z = lz - radius; z <= lz + radius; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material t = b.getType();
                    if (t == Material.SPRUCE_LOG) nearSpruce = true;
                    if (t == Material.BIRCH_LOG)  nearBirch  = true;
                }
            }
        }

        // Si solo hay pinos alrededor → taiga
        if (nearSpruce && !nearBirch) return Material.SPRUCE_LEAVES;
        // Si solo hay abedules alrededor → birch
        if (nearBirch && !nearSpruce) return Material.BIRCH_LEAVES;

        // Si hay mezcla, usamos el bioma para decidir
        if (isTaigaBiome(biome)) return Material.SPRUCE_LEAVES;
        if (isBirchBiome(biome)) return Material.BIRCH_LEAVES;

        // Sin pista clara → mejor no tocar
        return null;
    }

    private boolean isTaigaBiome(Biome biome) {
        return biome == Biome.TAIGA ||
                biome == Biome.SNOWY_TAIGA ||
                biome == Biome.OLD_GROWTH_SPRUCE_TAIGA ||
                biome == Biome.OLD_GROWTH_PINE_TAIGA;
    }

    private boolean isBirchBiome(Biome biome) {
        return biome == Biome.BIRCH_FOREST ||
                biome == Biome.OLD_GROWTH_BIRCH_FOREST;
    }

    /**
     * Pinta hojas en un chunk concreto, guardando el tipo original
     * para poder revertir después.
     */
    private void processChunk(World w, int cx, int cz,
                              boolean highDetail,
                              double paintFactor,
                              boolean matureAutumn,
                              boolean localGridFlip) {

        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();

        int baseX = cx << 4;
        int baseZ = cz << 4;

        // probabilidad efectiva de pintar por bloque
        double effectiveChance = leafChancePerBlock * paintFactor;
        if (effectiveChance <= 0.0) return;

        // stride:
        int stepXZ = (matureAutumn && highDetail) ? 1 : 2;
        int startOffset = 0;
        if (stepXZ == 2) {
            startOffset = localGridFlip ? 0 : 1; // un tick pares, otro impares
        }

        for (int lx = startOffset; lx < 16; lx += stepXZ) {
            for (int lz = startOffset; lz < 16; lz += stepXZ) {
                int wx = baseX + lx;
                int wz = baseZ + lz;

                int yTop = w.getHighestBlockYAt(wx, wz) - 1;
                if (yTop < minY) continue;

                int scanMinY = Math.max(minY, yTop - 12);
                int scanMaxY = Math.min(maxY, yTop + 8);

                for (int y = scanMinY; y <= scanMaxY; y++) {
                    Block b = w.getBlockAt(wx, y, wz);
                    Material type = b.getType();

                    if (!leafTypes.contains(type)) continue;
                    if (effectiveChance < 1.0 && random.nextDouble() > effectiveChance) continue;

                    paintLeaf(b, type);
                }
            }
        }
    }

    /**
     * Pinta una hoja concreta a ACACIA_LEAVES y guarda el tipo original.
     * IMPORTANTE: persistent = false para que el decay vanilla siga funcionando.
     */
    private void paintLeaf(Block b, Material originalType) {
        if (b.getType() == Material.ACACIA_LEAVES) {
            return;
        }
        String k = key(b.getWorld(), b.getX(), b.getY(), b.getZ());
        paintedLeaves.putIfAbsent(k, originalType);

        BlockData oldData = b.getBlockData();
        int distance = 1;
        if (oldData instanceof Leaves leavesOld) {
            distance = leavesOld.getDistance();
        }

        Leaves newLeaves = (Leaves) Material.ACACIA_LEAVES.createBlockData();
        newLeaves.setDistance(distance);
        newLeaves.setPersistent(false); // decay vanilla
        b.setBlockData(newLeaves, false);
    }

    /**
     * Cuando aparece un ítem en el mundo, si es un ACACIA_SAPLING y
     * viene de una hoja que nosotros pintamos (spruce/birch),
     * lo convertimos al sapling correcto.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSaplingSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        ItemStack stack = item.getItemStack();

        // Solo nos interesan saplings de acacia
        if (stack.getType() != Material.ACACIA_SAPLING) {
            return;
        }

        // Solo nos preocupa en otoño (y pre-otoño, si quieres)
        CalendarState st = seasons.getStateCopy();
        if (st.season != Season.AUTUMN && st.season != Season.SUMMER) {
            return;
        }

        // Bloque de donde básicamente viene el ítem
        Location loc = item.getLocation();
        World w = loc.getWorld();
        if (w == null) return;

        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        // Buscamos si tenemos registrada una hoja pintada en esa posición
        Material original = null;

        // primero el bloque donde está el ítem
        String k0 = key(w, bx, by, bz);
        original = paintedLeaves.get(k0);

        // si no, probamos un bloque más arriba (por si el ítem aparece ligeramente alto)
        if (original == null) {
            String k1 = key(w, bx, by + 1, bz);
            original = paintedLeaves.get(k1);
        }

        if (original == null) {
            // no era una hoja pintada nuestra
            return;
        }

        // Solo queremos corregir spruce y birch, el resto lo dejamos tal cual
        if (original != Material.SPRUCE_LEAVES && original != Material.BIRCH_LEAVES) {
            return;
        }

        Material correctSapling = saplingFor(original);
        if (correctSapling == null || correctSapling == Material.ACACIA_SAPLING) {
            return;
        }

        // Aquí cambiamos el ítem que ya iba a salir al sapling correcto
        stack.setType(correctSapling);
    }

    /**
     * Revertimos hasta "maxBlocks" hojas pintadas por tick,
     * sólo si el chunk está cargado.
     */
    private void revertSomeLeavesInChunk(World w, int cx, int cz, int maxBlocks) {
        if (maxBlocks <= 0) return;

        Iterator<Map.Entry<String, Material>> it = paintedLeaves.entrySet().iterator();
        while (it.hasNext() && maxBlocks > 0) {
            Map.Entry<String, Material> entry = it.next();
            String key = entry.getKey();
            Material original = entry.getValue();

            String[] parts = key.split(":");
            if (parts.length != 4) {
                it.remove();
                continue;
            }

            UUID worldId;
            try {
                worldId = UUID.fromString(parts[0]);
            } catch (IllegalArgumentException ex) {
                it.remove();
                continue;
            }

            World entryWorld = Bukkit.getWorld(worldId);
            if (entryWorld == null) {
                it.remove();
                continue;
            }

            int x, y, z;
            try {
                x = Integer.parseInt(parts[1]);
                y = Integer.parseInt(parts[2]);
                z = Integer.parseInt(parts[3]);
            } catch (NumberFormatException ex) {
                it.remove();
                continue;
            }

            if (entryWorld != w) {
                continue;
            }

            if ((x >> 4) != cx || (z >> 4) != cz) {
                continue;
            }

            if (!w.isChunkLoaded(cx, cz)) {
                continue;
            }

            Block b = w.getBlockAt(x, y, z);

            BlockData currentData = b.getBlockData();
            int distance = 1;
            if (currentData instanceof Leaves leavesCurrent) {
                distance = leavesCurrent.getDistance();
            }

            BlockData backData = original.createBlockData();
            if (backData instanceof Leaves backLeaves) {
                backLeaves.setDistance(distance);
                backLeaves.setPersistent(false);
                b.setBlockData(backLeaves, false);
            } else {
                b.setType(original, false);
            }

            it.remove();
            maxBlocks--;
        }
    }

    /**
     * Devuelve el sapling que corresponde a un tipo de hoja original.
     */
    private Material saplingFor(Material originalLeaves) {
        return switch (originalLeaves) {
            case SPRUCE_LEAVES      -> Material.SPRUCE_SAPLING;
            case BIRCH_LEAVES       -> Material.BIRCH_SAPLING;
            case OAK_LEAVES         -> Material.OAK_SAPLING;
            case DARK_OAK_LEAVES    -> Material.DARK_OAK_SAPLING;
            case JUNGLE_LEAVES      -> Material.JUNGLE_SAPLING;
            case ACACIA_LEAVES      -> Material.ACACIA_SAPLING;
            case CHERRY_LEAVES      -> Material.CHERRY_SAPLING;
            default -> null;
        };
    }

    private String key(World w, int x, int y, int z) {
        return w.getUID() + ":" + x + ":" + y + ":" + z;
    }

    /* ====================== HELPERS CALENDARIO ====================== */

    private int computeDayInSeason(CalendarState st) {
        // 1) Intentar método getDayInSeason() o dayInSeason()
        try {
            try {
                java.lang.reflect.Method m = st.getClass().getMethod("getDayInSeason");
                Object val = m.invoke(st);
                if (val instanceof Number n) {
                    return clampDayInSeason(n.intValue());
                }
            } catch (NoSuchMethodException ignored) {
                java.lang.reflect.Method m = st.getClass().getMethod("dayInSeason");
                Object val = m.invoke(st);
                if (val instanceof Number n) {
                    return clampDayInSeason(n.intValue());
                }
            }
        } catch (Exception ignored) {
        }

        // 2) Intentar campo dayInSeason
        try {
            for (java.lang.reflect.Field f : st.getClass().getDeclaredFields()) {
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (name.contains("dayinseason")) {
                    f.setAccessible(true);
                    Object v = f.get(st);
                    if (v instanceof Number n) {
                        return clampDayInSeason(n.intValue());
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 3) Intentar día global y plegarlo
        Integer dayGlobal = tryGetIntField(st, "dayOfYear", "day_of_year", "day");
        if (dayGlobal != null) {
            int d = ((dayGlobal - 1) % DAYS_PER_SEASON) + 1;
            return clampDayInSeason(d);
        }

        return 1;
    }

    private Integer tryGetIntField(CalendarState st, String... candidates) {
        try {
            for (String raw : candidates) {
                String name = raw;

                String getter = "get" + name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
                try {
                    java.lang.reflect.Method m = st.getClass().getMethod(getter);
                    Object v = m.invoke(st);
                    if (v instanceof Number n) {
                        return n.intValue();
                    }
                } catch (NoSuchMethodException ignored) {
                }

                try {
                    java.lang.reflect.Method m2 = st.getClass().getMethod(name);
                    Object v2 = m2.invoke(st);
                    if (v2 instanceof Number n2) {
                        return n2.intValue();
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }

            for (java.lang.reflect.Field f : st.getClass().getDeclaredFields()) {
                String fn = f.getName();
                for (String cand : candidates) {
                    if (fn.equalsIgnoreCase(cand)) {
                        f.setAccessible(true);
                        Object v = f.get(st);
                        if (v instanceof Number n) {
                            return n.intValue();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private int clampDayInSeason(int d) {
        if (d < 1) d = 1;
        if (d > DAYS_PER_SEASON) d = DAYS_PER_SEASON;
        return d;
    }

    /**
     * Factor 0..1 para verano días 26–28.
     * 26 → ~0.33, 27 → ~0.66, 28 → 1.0
     */
    private double computePreAutumnFactor(int dayInSeason) {
        if (dayInSeason < PRE_AUTUMN_START_DAY) return 0.0;
        int step = dayInSeason - PRE_AUTUMN_START_DAY; // 0..2
        double factor = (step + 1) / (double) PRE_AUTUMN_RAMP_DAYS;
        if (factor < 0.0) factor = 0.0;
        if (factor > 1.0) factor = 1.0;
        return factor;
    }
}
