package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.SeasonUpdateEvent;
import Kinkin.aeternum.lang.LanguageManager;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;


import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLocaleChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class VillagerTypeOverrides implements Listener {

    private final AeternumSeasonsPlugin plugin;
    private final LanguageManager langManager;

    private boolean enabled;
    private double overrideChance;
    private final Set<String> worlds = new HashSet<>();
    private final List<Villager.Type> allowedTypes = new ArrayList<>();

    // Lista de todos los posibles tags de holgazán para una limpieza robusta.
    private Set<String> allLazyTags = Collections.emptySet();

    // Día en el que se hizo la última rotación efectiva
    private int lastUpdateDay = -1;
    private static final int UPDATE_PERIOD = 5; // Cada 5 días
    private static final int VILLAGE_RANGE_CHUNKS = 10;
    private static final int MAX_LAZY_PER_AREA = 2; // Máx. 2 aldeanos "no trabajan" por zona

    // Día actual del calendario (lo vamos actualizando en SeasonUpdateEvent)
    private int currentCalendarDay = -1;

    public VillagerTypeOverrides(AeternumSeasonsPlugin plugin, LanguageManager langManager) {
        this.plugin = plugin;
        this.langManager = langManager;
        loadState();
        reloadFromConfig();
    }

    // --- Lógica de Estado Persistente ---

    private void loadState() {
        File f = new File(plugin.getDataFolder(), "data/villager_override_state.yml");
        if (!f.exists()) {
            this.lastUpdateDay = -1;
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        this.lastUpdateDay = y.getInt("last_update_day", -1);
    }

    private void saveState() {
        try {
            File f = new File(plugin.getDataFolder(), "data/villager_override_state.yml");
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            YamlConfiguration y = new YamlConfiguration();
            y.set("last_update_day", this.lastUpdateDay);
            y.save(f);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar villager_override_state.yml: " + e.getMessage());
        }
    }

    public void reloadFromConfig() {
        ConfigurationSection sec = plugin.cfg.climate.getConfigurationSection("villager_type_overrides");
        if (sec == null) {
            enabled = false;
            worlds.clear();
            allowedTypes.clear();
            return;
        }

        this.enabled = sec.getBoolean("enabled", false);
        this.overrideChance = sec.getDouble("override_chance", 1.0);

        // mundos donde aplica
        worlds.clear();
        for (String w : sec.getStringList("worlds")) {
            if (w != null && !w.isEmpty()) {
                worlds.add(w);
            }
        }

        // tipos permitidos
        allowedTypes.clear();
        for (String s : sec.getStringList("allowed_types")) {
            try {
                Villager.Type t = Villager.Type.valueOf(s.toUpperCase(Locale.ROOT));
                allowedTypes.add(t);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("[VillagerTypes] Invalid villager type '" + s + "' in allowed_types");
            }
        }

        if (allowedTypes.isEmpty()) {
            // fallback seguro
            allowedTypes.addAll(Arrays.asList(
                    Villager.Type.PLAINS,
                    Villager.Type.DESERT,
                    Villager.Type.SAVANNA,
                    Villager.Type.TAIGA,
                    Villager.Type.SNOW,
                    Villager.Type.SWAMP,
                    Villager.Type.JUNGLE
            ));
        }

        // Cargar todos los posibles tags de holgazán para una limpieza global.
        this.allLazyTags = langManager.getAllTranslations("villager.lazy_tag");

        plugin.getLogger().info("[VillagerTypes] Enabled=" + enabled
                + ", worlds=" + worlds
                + ", allowedTypes=" + allowedTypes);
    }

    // =========================================================================
    // Lógica para Aldeanos recién generados (Spawning/Huevos)
    // =========================================================================

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (!enabled) return;
        if (e.getEntityType() != EntityType.VILLAGER) return;

        CreatureSpawnEvent.SpawnReason reason = e.getSpawnReason();
        switch (reason) {
            case BREEDING, CURED, NATURAL, JOCKEY, COMMAND, SPAWNER_EGG -> { /* ok */ }
            default -> {
                return;
            }
        }

        World w = e.getLocation().getWorld();
        if (w == null || (!worlds.isEmpty() && !worlds.contains(w.getName()))) {
            return;
        }

        if (ThreadLocalRandom.current().nextDouble() > overrideChance) {
            return;
        }

        Villager villager = (Villager) e.getEntity();

        // Si el aldeano es Nivel 1, aplicamos el tipo aleatorio.
        if (villager.getVillagerLevel() <= 1) {
            Villager.Type newType = allowedTypes.get(
                    ThreadLocalRandom.current().nextInt(allowedTypes.size())
            );
            villager.setVillagerType(newType);
        }

        // Aplicar etiqueta al holgazán (si corresponde). Idioma por defecto del servidor.
        updateVillagerDisplay(villager, null);
    }

    // =========================================================================
    // Calendario: solo actualizamos el día actual
    // =========================================================================

    @EventHandler
    public void onSeasonUpdate(SeasonUpdateEvent e) {
        if (!enabled) return;
        CalendarState state = e.getState();
        this.currentCalendarDay = state.day;
    }

    // =========================================================================
    // Trigger por cercanía / movimiento / entrada al servidor
    // =========================================================================

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        if (!enabled) return;
        if (e.getTo() == null) return;

        // Sólo nos importa cuando cambia de chunk para no spamear
        Chunk from = e.getFrom().getChunk();
        Chunk to = e.getTo().getChunk();
        if (from.getX() == to.getX() && from.getZ() == to.getZ()) {
            return;
        }

        maybeUpdateVillagersIfPeriodPassed();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        if (!enabled) return;

        // Un pequeño delay para que el jugador cargue chunks
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (e.getPlayer().isOnline()) {
                maybeUpdateVillagersIfPeriodPassed();
            }
        }, 40L);
    }

    /**
     * Revisa si han pasado al menos UPDATE_PERIOD días desde la última rotación.
     * Si sí, ejecuta performVillageUpdate() alrededor de TODOS los jugadores
     * conectados en mundos válidos.
     */
    private void maybeUpdateVillagersIfPeriodPassed() {
        if (!enabled) return;
        if (currentCalendarDay <= 0) return;

        // ¿Hay al menos un jugador en un mundo configurado?
        boolean anyPlayerInConfiguredWorld = false;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (worlds.contains(p.getWorld().getName())) {
                anyPlayerInConfiguredWorld = true;
                break;
            }
        }
        if (!anyPlayerInConfiguredWorld) {
            return;
        }

        boolean shouldUpdate;
        if (lastUpdateDay == -1) {
            // Nunca se ha actualizado → primera vez
            shouldUpdate = true;
        } else if (currentCalendarDay >= lastUpdateDay) {
            // Misma estación: comprobamos diferencia de días
            shouldUpdate = (currentCalendarDay - lastUpdateDay) >= UPDATE_PERIOD;
        } else {
            // currentCalendarDay < lastUpdateDay → probablemente nueva estación/año
            // Forzamos una actualización para "resincronizar".
            shouldUpdate = true;
        }

        if (!shouldUpdate) return;

        int previousDay = lastUpdateDay;
        plugin.getLogger().info("[VillagerTypes] Have approved at least " + UPDATE_PERIOD
                + " days since the last rotation (" + previousDay + " -> " + currentCalendarDay + "). Updating villagers close to players.");

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            performVillageUpdate();
            lastUpdateDay = currentCalendarDay;
            saveState();
        });
    }

    // =========================================================================
    // Lógica para Aldeanos ya existentes (rotación cada X días)
    // =========================================================================

    private void performVillageUpdate() {
        if (allowedTypes.isEmpty()) return;

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            World w = p.getWorld();
            if (!worlds.contains(w.getName())) continue;

            Chunk pChunk = p.getLocation().getChunk();
            int r = VILLAGE_RANGE_CHUNKS;

            List<Villager> candidates = new ArrayList<>();

            // 1) Juntar TODOS los aldeanos candidatos (sin trade hecho)
            for (int x = pChunk.getX() - r; x <= pChunk.getX() + r; x++) {
                for (int z = pChunk.getZ() - r; z <= pChunk.getZ() + r; z++) {
                    if (!w.isChunkLoaded(x, z)) continue;

                    for (Entity entity : w.getChunkAt(x, z).getEntities()) {
                        if (entity.getType() != EntityType.VILLAGER) continue;

                        Villager villager = (Villager) entity;

                        // PROTEGIDO = ya tuvo al menos un trade
                        boolean hasTraded = villager.getVillagerLevel() > 1
                                || villager.getVillagerExperience() > 0;

                        if (hasTraded) {
                            // Este ya se queda como está, solo limpiamos/actualizamos el tag
                            updateVillagerDisplay(villager, p);
                            continue;
                        }

                        // No ha tradeado → entra al pool de reroll SIEMPRE,
                        // aunque actualmente sea NITWIT o tenga [Lazy]
                        candidates.add(villager);
                    }
                }
            }

            if (candidates.isEmpty()) {
                continue;
            }

            // 2) Cada ciclo rehacemos COMPLETAMENTE quién es vago y quién no
            Collections.shuffle(candidates, rnd);

            int total = candidates.size();
            int lazyToAssign = 0;

// Si hay 3 o más aldeanos, permitimos algunos lazy.
// Máx. 25% del total y nunca más de MAX_LAZY_PER_AREA.
            if (total >= 3) {
                int lazyByRatio = (int) Math.floor(total * 0.25); // 25%
                // Al menos 1 si el ratio dio 0
                lazyToAssign = Math.max(1, lazyByRatio);
                lazyToAssign = Math.min(MAX_LAZY_PER_AREA, lazyToAssign);

                // Seguridad: nunca todos lazy
                if (lazyToAssign >= total) {
                    lazyToAssign = total - 1;
                }
            }

            for (int i = 0; i < total; i++) {
                Villager v = candidates.get(i);

                if (i < lazyToAssign) {
                    // Holgazán
                    v.setProfession(Villager.Profession.NITWIT);
                } else {
                    // No vago, para que pueda agarrar mesa de trabajo
                    v.setProfession(Villager.Profession.NONE);
                }

                // Siempre re-rol de tipo (bioma del aldeano)
                Villager.Type newType = allowedTypes.get(
                        rnd.nextInt(allowedTypes.size())
                );
                v.setVillagerType(newType);

                // Actualizar nombre / tag [Lazy]
                updateVillagerDisplay(v, p);
            }

        }
    }

    // =========================================================================
    // Lógica para Etiqueta de Holgazán (Traducción Dinámica)
    // =========================================================================

    private void updateVillagerDisplay(Villager villager, Player p) {
        String lazyTag = langManager.tr(p, "villager.lazy_tag");

        if (villager.getProfession() == Villager.Profession.NITWIT) {
            String currentName = villager.getCustomName() != null ? villager.getCustomName() : "";

            String nameWithoutTag = removeAllLazyTags(currentName);

            if (nameWithoutTag.isEmpty()) {
                villager.setCustomName(lazyTag);
            } else {
                villager.setCustomName(nameWithoutTag + " " + lazyTag);
            }
            villager.setCustomNameVisible(true);
        } else {
            String currentName = villager.getCustomName();
            if (currentName != null) {
                String nameWithoutTag = removeAllLazyTags(currentName);
                if (nameWithoutTag.trim().isEmpty()) {
                    villager.setCustomName(null);
                    villager.setCustomNameVisible(false);
                } else if (!currentName.equals(nameWithoutTag)) {
                    villager.setCustomName(nameWithoutTag);
                    villager.setCustomNameVisible(true);
                }
            }
        }
    }

    private String removeAllLazyTags(String name) {
        if (name == null) return "";

        String cleanName = name;

        // Limpieza robusta para cualquier idioma.
        for (String tag : allLazyTags) {
            cleanName = cleanName.replace(tag, "").trim();
        }

        // También limpiamos el tag actual del servidor, por si cambió el idioma.
        String currentLazyTag = langManager.trServer("villager.lazy_tag");
        cleanName = cleanName.replace(currentLazyTag, "").trim();

        return cleanName;
    }

    @EventHandler
    public void onPlayerLocaleChange(PlayerLocaleChangeEvent e) {
        if (!enabled) return;
        // Solo aplica la etiqueta de holgazán a los aldeanos cercanos, no cambia el tipo.
        checkNearbyVillagers(e.getPlayer());
    }

    private void checkNearbyVillagers(Player p) {
        World w = p.getWorld();
        if (!worlds.contains(w.getName())) return;

        Chunk pChunk = p.getLocation().getChunk();

        int r = VILLAGE_RANGE_CHUNKS;
        for (int x = pChunk.getX() - r; x <= pChunk.getX() + r; x++) {
            for (int z = pChunk.getZ() - r; z <= pChunk.getZ() + r; z++) {
                if (!w.isChunkLoaded(x, z)) continue;

                for (Entity entity : w.getChunkAt(x, z).getEntities()) {
                    if (entity.getType() == EntityType.VILLAGER) {
                        updateVillagerDisplay((Villager) entity, p);
                    }
                }
            }
        }
    }
}
