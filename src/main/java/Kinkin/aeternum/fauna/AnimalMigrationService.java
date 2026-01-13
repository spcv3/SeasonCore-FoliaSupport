package Kinkin.aeternum.fauna;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.HeightMap;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Migraciones estacionales de animales.
 *
 * - Ajusta spawns naturales según estación (boost a ciertos animales).
 * - Migra animales a biomas más apropiados (teleport suave con partículas).
 * - "Despawn suave" de fauna de clima cálido en invierno.
 */
public final class AnimalMigrationService implements Listener, Runnable {

    private final AeternumSeasonsPlugin plugin;
    private final SeasonService seasons;
    private WrappedTask task;
    private final Random random = new Random();

    private boolean enabled;
    private long tickPeriod;
    private int animalsPerTick;
    private int searchRadiusBlocks;
    private boolean showParticles;

    private double springSpawnBoostChance;
    private double summerSpawnBoostChance;
    private double autumnSpawnBoostChance;
    private double winterSpawnBoostChance;

    private double winterWarmAnimalCullChance;

    // Limpieza fuerte al inicio del invierno
    private boolean winterCleanupEnabled;
    private int winterCleanupMaxDays;        // cuántos días dura la limpieza (ej. 3)
    private int winterCleanupBaseRadius;     // radio día 1
    private int winterCleanupRadiusStep;     // cuánto aumenta el radio por día
    private int winterCleanupPerTick;        // presupuesto de eliminaciones por tick

    // Listas de tipos preferidos por estación (para boosts de spawn)
    private final EnumSet<EntityType> springBoost = EnumSet.noneOf(EntityType.class);
    private final EnumSet<EntityType> summerBoost = EnumSet.noneOf(EntityType.class);
    private final EnumSet<EntityType> autumnBoost = EnumSet.noneOf(EntityType.class);
    private final EnumSet<EntityType> winterBoost = EnumSet.noneOf(EntityType.class);

    // Animales de clima cálido (sufren en invierno)
    private final EnumSet<EntityType> warmClimateAnimals = EnumSet.noneOf(EntityType.class);
    // Animales de clima frío (tienen biomas favoritos fríos)
    private final EnumSet<EntityType> coldClimateAnimals = EnumSet.noneOf(EntityType.class);

    public AnimalMigrationService(AeternumSeasonsPlugin plugin, SeasonService seasons) {
        this.plugin = plugin;
        this.seasons = seasons;
        initSets();
        reloadFromConfig();
    }

    /* =================== CONFIG / REGISTRO =================== */

    private void initSets() {
        // Primavera: ovejas, conejos, abejas, vacas, etc.
        springBoost.addAll(Arrays.asList(
                EntityType.SHEEP,
                EntityType.RABBIT,
                EntityType.BEE,
                EntityType.COW,
                EntityType.PIG,
                EntityType.CHICKEN,
                EntityType.HORSE,
                EntityType.DONKEY,
                EntityType.LLAMA,
                EntityType.CAMEL,
                EntityType.VILLAGER,
                EntityType.SNIFFER
        ));

        // Verano: vida marina cálida y bichos veraniegos
        summerBoost.addAll(Arrays.asList(
                EntityType.TROPICAL_FISH,
                EntityType.COD,
                EntityType.SALMON,
                EntityType.DOLPHIN,
                EntityType.TURTLE,
                EntityType.AXOLOTL,
                EntityType.FROG,
                EntityType.PARROT,
                EntityType.PANDA
        ));

        // Otoño: fauna de bosques fríos
        autumnBoost.addAll(Arrays.asList(
                EntityType.FOX,
                EntityType.WOLF,
                EntityType.GOAT,
                EntityType.MOOSHROOM,
                EntityType.MULE,
                EntityType.CAT
        ));

        // Invierno: lobos, osos polares, golem de nieve, caballos esqueléticos…
        winterBoost.addAll(Arrays.asList(
                EntityType.WOLF,
                EntityType.POLAR_BEAR,
                EntityType.SNOW_GOLEM,
                EntityType.SKELETON_HORSE,
                EntityType.STRAY,
                EntityType.STRIDER
        ));

        // Clima cálido
        warmClimateAnimals.addAll(Arrays.asList(
                EntityType.BEE,
                EntityType.PARROT,
                EntityType.TROPICAL_FISH,
                EntityType.PANDA,
                EntityType.FROG,
                EntityType.AXOLOTL,
                EntityType.CAMEL,
                EntityType.TURTLE,
                EntityType.DOLPHIN,
                EntityType.HOGLIN,
                EntityType.STRIDER,
                EntityType.MAGMA_CUBE
        ));

        // Clima frío
        coldClimateAnimals.addAll(Arrays.asList(
                EntityType.POLAR_BEAR,
                EntityType.GOAT,
                EntityType.FOX,
                EntityType.SNOW_GOLEM,
                EntityType.SKELETON_HORSE
        ));
    }

    private void reloadFromConfig() {
        var y = plugin.cfg.climate;

        enabled            = y.getBoolean("migration.enabled", true);
        tickPeriod         = y.getLong("migration.tick_period", 200L);
        animalsPerTick     = y.getInt("migration.animals_per_tick", 40);
        searchRadiusBlocks = y.getInt("migration.search_radius_blocks", 160);
        showParticles      = y.getBoolean("migration.particles", true);

        springSpawnBoostChance = y.getDouble("migration.spawn.spring_boost_chance", 0.25);
        summerSpawnBoostChance = y.getDouble("migration.spawn.summer_boost_chance", 0.25);
        autumnSpawnBoostChance = y.getDouble("migration.spawn.autumn_boost_chance", 0.20);
        winterSpawnBoostChance = y.getDouble("migration.spawn.winter_boost_chance", 0.18);

        winterWarmAnimalCullChance = y.getDouble("migration.soft_despawn.warm_in_winter_chance", 0.25);

        // Limpieza de fauna cálida al inicio de invierno
        winterCleanupEnabled    = y.getBoolean("migration.winter_cleanup.enabled", true);
        winterCleanupMaxDays    = y.getInt("migration.winter_cleanup.days", 3);
        if (winterCleanupMaxDays < 1) winterCleanupMaxDays = 1;

        winterCleanupBaseRadius = y.getInt("migration.winter_cleanup.base_radius_blocks", 64);
        winterCleanupRadiusStep = y.getInt("migration.winter_cleanup.radius_step_blocks", 64);
        winterCleanupPerTick    = y.getInt("migration.winter_cleanup.max_per_tick", animalsPerTick);
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (task != null) task.cancel();
        if (!enabled) return;
        task = plugin.getScheduler().runTimer(this, 80L, tickPeriod);
    }

    public void unregister() {
        if (task != null) task.cancel();
        HandlerList.unregisterAll(this);
    }

    /* =================== AJUSTE DE SPAWNS =================== */

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (!enabled) return;
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL &&
                e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CHUNK_GEN &&
                e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) {
            return;
        }
        if (e.getLocation().getWorld().getEnvironment() != World.Environment.NORMAL &&
                e.getLocation().getWorld().getEnvironment() != World.Environment.NETHER) {
            return;
        }

        CalendarState st = seasons.getStateCopy();
        EntityType type = e.getEntityType();

        switch (st.season) {
            case SPRING -> handleBoost(e, springBoost, springSpawnBoostChance);
            case SUMMER -> handleBoost(e, summerBoost, summerSpawnBoostChance);
            case AUTUMN -> handleBoost(e, autumnBoost, autumnSpawnBoostChance);
            case WINTER -> handleBoost(e, winterBoost, winterSpawnBoostChance);
        }

        // invierno: recortar algo de fauna de clima cálido
        if (st.season == Season.WINTER &&
                warmClimateAnimals.contains(type) &&
                e.getLocation().getWorld().getEnvironment() == World.Environment.NORMAL) {
            if (random.nextDouble() < winterWarmAnimalCullChance) {
                e.setCancelled(true);
            }
        }
    }

    private void handleBoost(CreatureSpawnEvent e,
                             EnumSet<EntityType> set, double chance) {
        if (!set.contains(e.getEntityType())) return;
        if (random.nextDouble() >= chance) return;

        Location loc = e.getLocation();
        Location extra = loc.clone().add(randomOffset(3), 0, randomOffset(3));

        int cx = extra.getBlockX() >> 4;
        int cz = extra.getBlockZ() >> 4;
        if (!loc.getWorld().isChunkLoaded(cx, cz)) {
            return; // o simplemente no hacer el "extra spawn"
        }

        extra.setY(loc.getWorld().getHighestBlockYAt(extra, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1);
        Entity spawned = loc.getWorld().spawnEntity(extra, e.getEntityType());
        if (spawned instanceof Ageable ageable) {
            if (random.nextBoolean()) ageable.setBaby();
        }
        if (showParticles) {
            loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                    extra, 8, 0.5, 0.5, 0.5, 0.01);
        }
    }

    private double randomOffset(int max) {
        return (random.nextDouble() * 2 - 1) * max;
    }

    /* =================== RUN: MIGRACIONES PERIÓDICAS =================== */

    @Override
    public void run() {
        if (!enabled) return;
        CalendarState st = seasons.getStateCopy();

        if (plugin.getFoliaLib().isFolia()) {
            runFoliaTick(st);
            return;
        }

        // Limpieza fuerte de fauna cálida en los primeros N días de invierno
        if (st.season == Season.WINTER && winterCleanupEnabled && st.day <= winterCleanupMaxDays) {
            performWinterCleanup(st);
            return; // este tick sólo hacemos limpieza, no migración normal
        }

        int budget = animalsPerTick;

        for (World w : Bukkit.getWorlds()) {
            if (budget <= 0) break;
            if (w.getEnvironment() != World.Environment.NORMAL &&
                    w.getEnvironment() != World.Environment.NETHER) continue;

            for (LivingEntity le : w.getLivingEntities()) {
                if (budget <= 0) break;
                if (!isInterestingAnimal(le)) continue;
                if (!le.isValid() || le.isDead()) continue;

                handleMigrationFor(st, le);
                budget--;
            }
        }
    }

    private void runFoliaTick(CalendarState st) {
        if (st.season == Season.WINTER && winterCleanupEnabled && st.day <= winterCleanupMaxDays) {
            foliaWinterCleanup(st);
            return;
        }

        java.util.concurrent.atomic.AtomicInteger budget = new java.util.concurrent.atomic.AtomicInteger(animalsPerTick);
        if (budget.get() <= 0) return;

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) return;
        Collections.shuffle(players, ThreadLocalRandom.current());

        for (Player p : players) {
            if (budget.get() <= 0) break;
            plugin.getScheduler().runAtEntity(p, task -> processMigrationsNearPlayer(p, st, budget));
        }
    }

    private void processMigrationsNearPlayer(Player p, CalendarState st,
                                             java.util.concurrent.atomic.AtomicInteger budget) {
        if (!p.isOnline()) return;
        if (budget.get() <= 0) return;

        World w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL &&
                w.getEnvironment() != World.Environment.NETHER) return;

        int pcx = p.getLocation().getBlockX() >> 4;
        int pcz = p.getLocation().getBlockZ() >> 4;

        for (Entity ent : p.getNearbyEntities(searchRadiusBlocks, searchRadiusBlocks, searchRadiusBlocks)) {
            if (budget.get() <= 0) break;
            if (!(ent instanceof LivingEntity le)) continue;
            if (!isInterestingAnimal(le)) continue;
            if (!le.isValid() || le.isDead()) continue;

            int ecx = le.getLocation().getBlockX() >> 4;
            int ecz = le.getLocation().getBlockZ() >> 4;
            if (ecx != pcx || ecz != pcz) continue;

            handleMigrationFor(st, le);
            budget.decrementAndGet();
        }
    }

    private void foliaWinterCleanup(CalendarState st) {
        int dayIndex = Math.max(1, Math.min(st.day, winterCleanupMaxDays));

        int radius = winterCleanupBaseRadius
                + (dayIndex - 1) * winterCleanupRadiusStep;

        java.util.concurrent.atomic.AtomicInteger budget = new java.util.concurrent.atomic.AtomicInteger(winterCleanupPerTick);
        if (budget.get() <= 0) return;

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) return;
        Collections.shuffle(players, ThreadLocalRandom.current());

        for (Player p : players) {
            if (budget.get() <= 0) break;
            plugin.getScheduler().runAtEntity(p, task -> cleanupNearPlayer(p, radius, budget));
        }
    }

    private void cleanupNearPlayer(Player p, int radius,
                                   java.util.concurrent.atomic.AtomicInteger budget) {
        if (!p.isOnline()) return;
        if (budget.get() <= 0) return;

        World w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return;

        int pcx = p.getLocation().getBlockX() >> 4;
        int pcz = p.getLocation().getBlockZ() >> 4;
        double maxDistSq = radius * (double) radius;

        for (Entity ent : p.getNearbyEntities(radius, radius, radius)) {
            if (budget.get() <= 0) break;
            if (!(ent instanceof LivingEntity le)) continue;
            if (!isInterestingAnimal(le)) continue;
            if (!le.isValid() || le.isDead()) continue;

            int ecx = le.getLocation().getBlockX() >> 4;
            int ecz = le.getLocation().getBlockZ() >> 4;
            if (ecx != pcx || ecz != pcz) continue;

            EntityType type = le.getType();
            if (!warmClimateAnimals.contains(type)) continue;
            if (coldClimateAnimals.contains(type)) continue;

            if (le.getLocation().distanceSquared(p.getLocation()) > maxDistSq) continue;

            softRemove(le);
            budget.decrementAndGet();
        }
    }

    /**
     * Limpieza progresiva de fauna de clima cálido al inicio del invierno.
     *
     * Día 1 → radio base
     * Día 2 → radio base + step
     * Día 3 → radio base + 2*step
     *
     * Sólo afecta fauna de clima cálido (warmClimateAnimals) en el Overworld,
     * y jamás toca los animales de clima frío (coldClimateAnimals).
     */
    private void performWinterCleanup(CalendarState st) {
        int dayIndex = Math.max(1, Math.min(st.day, winterCleanupMaxDays));

        int radius = winterCleanupBaseRadius
                + (dayIndex - 1) * winterCleanupRadiusStep;

        int budget = winterCleanupPerTick;
        if (budget <= 0) return;

        // Para no procesar el mismo mob varias veces si hay varios jugadores
        Set<UUID> visited = new HashSet<>();

        for (World w : Bukkit.getWorlds()) {
            if (budget <= 0) break;
            if (w.getEnvironment() != World.Environment.NORMAL) continue;

            for (Player p : w.getPlayers()) {
                if (budget <= 0) break;

                Location center = p.getLocation();
                double maxDistSq = radius * (double) radius;

                for (LivingEntity le : w.getLivingEntities()) {
                    if (budget <= 0) break;
                    if (!isInterestingAnimal(le)) continue;
                    if (!le.isValid() || le.isDead()) continue;

                    // evitar duplicados si varios jugadores comparten radio
                    if (!visited.add(le.getUniqueId())) continue;

                    EntityType type = le.getType();

                    // Sólo fauna de clima cálido "plantada" que queremos limpiar
                    if (!warmClimateAnimals.contains(type)) continue;
                    // Nunca tocamos fauna fría
                    if (coldClimateAnimals.contains(type)) continue;

                    if (le.getLocation().distanceSquared(center) > maxDistSq) continue;

                    softRemove(le);
                    budget--;
                }
            }
        }
    }

    private boolean isInterestingAnimal(LivingEntity le) {
        EntityType t = le.getType();
        if (t == EntityType.PLAYER || t == EntityType.IRON_GOLEM) return false;

        return switch (t) {
            case COW, PIG, SHEEP, CHICKEN, RABBIT, BEE,
                 HORSE, DONKEY, MULE, LLAMA, CAMEL,
                 WOLF, FOX, CAT, OCELOT, PARROT, PANDA,
                 GOAT, POLAR_BEAR, SNOW_GOLEM, SNIFFER,
                 TROPICAL_FISH, COD, SALMON, PUFFERFISH,
                 DOLPHIN, TURTLE, AXOLOTL, FROG,
                 SQUID, GLOW_SQUID,
                 STRIDER, HOGLIN, ZOGLIN, STRAY,
                 SKELETON_HORSE, ZOMBIE_HORSE,
                 RAVAGER, ALLAY, VILLAGER -> true;
            default -> false;
        };
    }

    private void handleMigrationFor(CalendarState st, LivingEntity e) {
        World w = e.getWorld();
        Biome biome = w.getBiome(e.getLocation());

        EntityType type = e.getType();

        switch (st.season) {
            case SPRING -> migrateSpring(e, biome, type);
            case SUMMER -> migrateSummer(e, biome, type);
            case AUTUMN -> migrateAutumn(e, biome, type);
            case WINTER -> migrateWinter(e, biome, type);
        }
    }

    /* ===== por estación ===== */

    private void migrateSpring(LivingEntity e, Biome biome, EntityType type) {
        if (!warmClimateAnimals.contains(type)) return;

        // mover a praderas/bosques florales
        if (random.nextDouble() < 0.05) {
            Location dst = findNearbyBiome(e.getLocation(), this::isSpringDestinationBiome);
            if (dst != null) teleportWithEffect(e, dst);
        }
    }

    private void migrateSummer(LivingEntity e, Biome biome, EntityType type) {
        if (!(type == EntityType.TROPICAL_FISH ||
                type == EntityType.DOLPHIN ||
                type == EntityType.TURTLE ||
                type == EntityType.AXOLOTL)) return;

        if (isWarmOceanBiome(biome)) return;

        if (random.nextDouble() < 0.12) {
            Location dst = findNearbyBiome(e.getLocation(), this::isWarmOceanBiome);
            if (dst != null) teleportWithEffect(e, dst);
        }
    }

    private void migrateAutumn(LivingEntity e, Biome biome, EntityType type) {
        if (!(type == EntityType.FOX || type == EntityType.WOLF || type == EntityType.GOAT)) return;

        if (isTaigaOrMountainBiome(biome) || isColdBiome(biome)) return;

        if (random.nextDouble() < 0.10) {
            Location dst = findNearbyBiome(e.getLocation(), this::isTaigaOrMountainBiome);
            if (dst != null) teleportWithEffect(e, dst);
        }
    }

    private void migrateWinter(LivingEntity e, Biome biome, EntityType type) {
        if (warmClimateAnimals.contains(type)) {
            if (isColdBiome(biome) && random.nextDouble() < 0.16) {
                Location dst = findNearbyBiome(e.getLocation(), this::isTemperateOrWarmBiome);
                if (dst != null) {
                    teleportWithEffect(e, dst);
                } else if (random.nextDouble() < 0.3) {
                    softRemove(e);
                }
            }
        } else if (coldClimateAnimals.contains(type)) {
            if (!isColdBiome(biome) && random.nextDouble() < 0.14) {
                Location dst = findNearbyBiome(e.getLocation(), this::isColdBiome);
                if (dst != null) teleportWithEffect(e, dst);
            }
        }
    }

    /* ===== helpers clima / biomas ===== */

    private String biomeKey(Biome b) {
        // En versiones nuevas puede ser nombre "minecraft:plains", en otras "PLAINS"
        String s = b.toString().toUpperCase(Locale.ROOT);
        return s;
    }

    private boolean containsAny(Biome b, String... tokens) {
        String key = biomeKey(b);
        for (String t : tokens) {
            if (key.contains(t)) return true;
        }
        return false;
    }

    private boolean isWarmBiome(Biome b) {
        return containsAny(b,
                "DESERT", "SAVANNA", "BADLANDS",
                "JUNGLE", "BAMBOO_JUNGLE");
    }

    private boolean isTemperateBiome(Biome b) {
        return containsAny(b,
                "PLAINS", "FOREST", "BIRCH_FOREST", "DARK_FOREST",
                "FLOWER_FOREST", "MEADOW");
    }

    private boolean isTemperateOrWarmBiome(Biome b) {
        return isTemperateBiome(b) || isWarmBiome(b);
    }

    private boolean isColdBiome(Biome b) {
        return containsAny(b,
                "SNOW", "FROZEN", "COLD", "ICE", "PEAKS", "SLOPES", "GROVE");
    }

    private boolean isTaigaOrMountainBiome(Biome b) {
        return containsAny(b,
                "TAIGA", "WINDSWEPT_HILLS", "WINDSWEPT_FOREST",
                "JAGGED_PEAKS", "MOUNTAIN", "GROVE", "SLOPES", "PEAKS");
    }

    private boolean isWarmOceanBiome(Biome b) {
        return containsAny(b,
                "WARM_OCEAN", "LUKEWARM_OCEAN");
    }

    // destinos "bonitos" para primavera
    private boolean isSpringDestinationBiome(Biome b) {
        return containsAny(b,
                "PLAINS", "SUNFLOWER_PLAINS", "FLOWER_FOREST", "MEADOW");
    }

    /* ===== búsqueda de biomas destino ===== */

    private Location findNearbyBiome(Location origin,
                                     java.util.function.Predicate<Biome> predicate) {
        if (plugin.getFoliaLib().isFolia()) {
            return findNearbyBiomeFolia(origin, predicate);
        }

        World w = origin.getWorld();
        int radius = searchRadiusBlocks;
        int tries  = 40;

        for (int i = 0; i < tries; i++) {
            int dx = random.nextInt(radius * 2 + 1) - radius;
            int dz = random.nextInt(radius * 2 + 1) - radius;
            int x = origin.getBlockX() + dx;
            int z = origin.getBlockZ() + dz;

            // ✅ CLAVE: NO fuerces carga/generación de chunk
            int cx = x >> 4;
            int cz = z >> 4;
            if (!w.isChunkLoaded(cx, cz)) continue;

            // Ya está cargado, ahora sí podemos consultar
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);

            Biome b = w.getBiome(x, y, z);
            if (!predicate.test(b)) continue;

            Location loc = new Location(w, x + 0.5, y + 1, z + 0.5);

            // Esto también toca bloques: pero como el chunk está cargado, no congela
            if (loc.getBlock().isPassable() && loc.clone().add(0, 1, 0).getBlock().isPassable()) {
                return loc;
            }
        }
        return null;
    }

    private Location findNearbyBiomeFolia(Location origin,
                                          java.util.function.Predicate<Biome> predicate) {
        World w = origin.getWorld();
        int cx = origin.getBlockX() >> 4;
        int cz = origin.getBlockZ() >> 4;

        if (!w.isChunkLoaded(cx, cz)) return null;

        int baseX = cx << 4;
        int baseZ = cz << 4;

        int tries = 24;
        for (int i = 0; i < tries; i++) {
            int x = baseX + random.nextInt(16);
            int z = baseZ + random.nextInt(16);
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);

            Biome b = w.getBiome(x, y, z);
            if (!predicate.test(b)) continue;

            Location loc = new Location(w, x + 0.5, y + 1, z + 0.5);
            if (loc.getBlock().isPassable() && loc.clone().add(0, 1, 0).getBlock().isPassable()) {
                return loc;
            }
        }
        return null;
    }

    private void teleportWithEffect(LivingEntity e, Location dst) {
        Location src = e.getLocation().clone();
        e.teleport(dst);

        if (showParticles) {
            World w = src.getWorld();
            w.spawnParticle(Particle.CLOUD, src, 20, 0.5, 0.5, 0.5, 0.01);
            w.spawnParticle(Particle.CLOUD, dst, 20, 0.5, 0.5, 0.5, 0.01);
        }
        e.getWorld().playSound(dst, Sound.ENTITY_ENDERMAN_TELEPORT, 0.4f, 1.3f);
    }

    private void softRemove(LivingEntity e) {
        if (showParticles) {
            e.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE,
                    e.getLocation().add(0, 0.5, 0),
                    12, 0.3, 0.3, 0.3, 0.01);
        }
        e.remove();
    }
}
