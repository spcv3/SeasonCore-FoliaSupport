package Kinkin.aeternum.farming;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

public final class GreenhouseService {

    private final SeasonalCropConfig config;

    public GreenhouseService(SeasonalCropConfig config) {
        this.config = config;
    }

    public boolean isInGreenhouse(Block crop) {
        if (!config.isGreenhouseEnabled() || config.getGreenhouseBlocks().isEmpty()) return false;

        World w = crop.getWorld();
        int cx = crop.getX();
        int cy = crop.getY();
        int cz = crop.getZ();

        boolean hasCore = !config.isGreenhouseRequireCore();
        int glassCount = 0;

        // OJO: usa maxRoofHeight como altura máxima que vamos a escanear
        int maxY = Math.min(cy + config.getMaxRoofHeight(), w.getMaxHeight() - 1);

        // Contamos cristal y buscamos núcleo alrededor de la planta
        for (int dx = -config.getGreenhouseRadius(); dx <= config.getGreenhouseRadius(); dx++) {
            for (int dz = -config.getGreenhouseRadius(); dz <= config.getGreenhouseRadius(); dz++) {
                for (int y = cy + 1; y <= maxY; y++) { // +1 para no contar el propio cultivo
                    Block b = w.getBlockAt(cx + dx, y, cz + dz);
                    Material mat = b.getType();

                    if (mat == Material.AIR || mat == Material.CAVE_AIR || mat == Material.VOID_AIR) {
                        continue;
                    }

                    if (config.getGreenhouseBlocks().contains(mat)) {
                        glassCount++;
                    }

                    if (config.getGreenhouseCoreBlock() != null && mat == config.getGreenhouseCoreBlock()) {
                        hasCore = true;
                    }
                }
            }
        }

        // Ya no pedimos canSeeSky para el "bubble": sirve también bajo una cúpula cerrada
        boolean bubble = glassCount >= config.getGreenhouseMinGlass() && hasCore;
        boolean roof = hasGlassRoof(crop);

        return bubble || roof;
    }

    /**
     * Ve si el cultivo está "a cielo abierto" para lluvia/nieve.
     * El cristal del invernadero cuenta como techo, así que NO ve el cielo.
     */
    public boolean canSeeSky(Block crop) {
        World w = crop.getWorld();
        int cx = crop.getX();
        int cz = crop.getZ();
        int startY = crop.getY() + 1;

        for (int y = w.getMaxHeight() - 1; y >= startY; y--) {
            Material mat = w.getBlockAt(cx, y, cz).getType();

            if (mat == Material.AIR || mat == Material.CAVE_AIR || mat == Material.VOID_AIR) {
                continue;
            }

            // Si lo primero que encontramos es cristal del invernadero,
            // entonces NO ve el cielo ni la lluvia.
            if (config.getGreenhouseBlocks().contains(mat)) {
                return false;
            }

            // Cualquier otro bloque también tapa el cielo (cueva, techo de piedra, etc.)
            return false;
        }

        // No hay nada arriba, está completamente al aire libre
        return true;
    }

    /**
     * Techo “simple”: cualquier bloque de greenhouseBlocks en la columna del cultivo,
     * hasta maxRoofHeight, mientras no haya otro bloque sólido distinto antes.
     * La nieve NO cancela el invernadero.
     */
    private boolean hasGlassRoof(Block crop) {
        World w = crop.getWorld();
        int cx = crop.getX();
        int cz = crop.getZ();
        int startY = crop.getY() + 1;
        int maxY = Math.min(startY + config.getMaxRoofHeight(), w.getMaxHeight() - 1);

        boolean foundGlass = false;

        for (int y = startY; y <= maxY; y++) {
            Block b = w.getBlockAt(cx, y, cz);
            Material mat = b.getType();

            if (mat == Material.AIR || mat == Material.CAVE_AIR || mat == Material.VOID_AIR) {
                continue;
            }

            // Techo de cristal válido: activamos el invernadero
            if (config.getGreenhouseBlocks().contains(mat)) {
                foundGlass = true;
                break;
            }

            // La nieve (capas o bloque) no rompe el invernadero
            if (mat == Material.SNOW || mat == Material.SNOW_BLOCK) {
                continue;
            }

            // Cualquier otro bloque sólido antes del cristal cancela el invernadero
            if (mat.isSolid()) {
                return false;
            }
        }

        return foundGlass;
    }

    /**
     * Solo está bajo la lluvia si hay tormenta y realmente ve el cielo.
     * Bajo el invernadero siempre devuelve false.
     */
    public boolean isDirectlyUnderRain(Block crop) {
        World w = crop.getWorld();
        if (!w.hasStorm()) return false;
        // Si está en invernadero, nunca cuenta como bajo la lluvia
        if (isInGreenhouse(crop)) return false;
        return canSeeSky(crop);
    }
}
