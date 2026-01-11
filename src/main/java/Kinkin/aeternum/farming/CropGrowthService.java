package Kinkin.aeternum.farming;

import Kinkin.aeternum.calendar.CalendarState;
import Kinkin.aeternum.calendar.Season;
import Kinkin.aeternum.calendar.SeasonService;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.EnumSet;
import java.util.concurrent.ThreadLocalRandom;

public final class CropGrowthService {

    public static final class GrowthDecision {
        private final boolean cancel;
        private final int extraAges;

        public GrowthDecision(boolean cancel, int extraAges) {
            this.cancel = cancel;
            this.extraAges = extraAges;
        }

        public boolean cancel() {
            return cancel;
        }

        public int extraAges() {
            return extraAges;
        }
    }

    private final SeasonalCropConfig config;
    private final GreenhouseService greenhouse;
    private final SeasonService seasons;

    public CropGrowthService(SeasonalCropConfig config,
                             GreenhouseService greenhouse,
                             SeasonService seasons) {
        this.config = config;
        this.greenhouse = greenhouse;
        this.seasons = seasons;
    }

    public GrowthDecision evaluate(Block b) {
        Material m = b.getType();

        if (!config.isManagedCrop(m)) {
            // No tocamos cultivos que no están en crops.yml
            return new GrowthDecision(false, 0);
        }

        EnumSet<Season> allowed = config.getAllowedSeasons(m);
        if (allowed == null || allowed.isEmpty()) {
            // Sin estaciones configuradas → no se aplica lógica
            return new GrowthDecision(false, 0);
        }

        CalendarState st = seasons.getStateCopy();
        Season season = st.season;

        boolean inGreenhouse = greenhouse.isInGreenhouse(b);
        boolean seasonOk = allowed.contains(season);

        // ** MODIFICACIÓN CLAVE **
        // Mueve la comprobación de si ve el cielo (seesSky) para usarla aquí.
        boolean seesSky = greenhouse.canSeeSky(b);

        // CANCELACIÓN: Solo cancelamos si NO es la estación correcta, NO está en invernadero, Y VE EL CIELO
        // (es decir, está totalmente a la intemperie).
        if (!seasonOk && !inGreenhouse && seesSky) {
            return new GrowthDecision(true, 0);
        }
        // ** FIN MODIFICACIÓN CLAVE **

        // Empezamos con velocidad base 1.0 (crecimiento normal de Minecraft)
        double speed = 1.0;

        // Luz
        int light = b.getLightLevel();
        if (light < config.getRequiredLight()) {
            speed *= config.getLowLightMultiplier(); // muy lento con poca luz
        }

        // Subsuelo: sin ver el cielo y sin invernadero → lento
        // seesSky ya fue definida antes.
        if (!seesSky && !inGreenhouse) {
            speed *= config.getUndergroundMultiplier();
        }

        // Lluvia: solo mejora cultivos al aire libre (no invernadero)
        if (greenhouse.isDirectlyUnderRain(b) && !inGreenhouse) {
            speed += config.getRainBonus(); // +1 = el doble de rápido
        }

        // Invernadero en invierno: turbo
        if (inGreenhouse && season == Season.WINTER) {
            speed += config.getWinterGreenhouseBonus();
        }

        // Si la velocidad es <= 0 → cancelamos
        if (speed <= 0.0) {
            return new GrowthDecision(true, 0);
        }

        // Convertimos speed en probabilidad / extra edades
        // speed < 1 → chance de que este tick cuente o se cancele
        // speed >= 1 → 1 edad normal + edades extra
        double r = ThreadLocalRandom.current().nextDouble();

        if (speed < 1.0) {
            if (r > speed) {
                // este tick se pierde, no crece
                return new GrowthDecision(true, 0);
            } else {
                // crece 1 edad normal, sin extra
                return new GrowthDecision(false, 0);
            }
        } else {
            double over = speed - 1.0;
            int extra = (int) Math.floor(over);
            double frac = over - extra;
            if (r < frac) {
                extra++;
            }
            return new GrowthDecision(false, extra);
        }
    }
}