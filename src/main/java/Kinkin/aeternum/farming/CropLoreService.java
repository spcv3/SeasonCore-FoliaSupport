package Kinkin.aeternum.farming;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import Kinkin.aeternum.calendar.Season;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.stream.Collectors;

public final class CropLoreService {

    private final AeternumSeasonsPlugin plugin;
    private final SeasonalCropConfig config;
    private final NamespacedKey KEY_MARK;

    // PALABRAS CLAVE EXACTAS de "grows_in:" en TODOS tus idiomas
    private static final Set<String> SEASON_PREFIXES = createSeasonPrefixes();

    // Palabras de "any_season" en todos los idiomas
    private static final Set<String> ANY_SEASON_KEYWORDS = createAnySeasonKeywords();

    // Palabras clave de greenhouse/invernadero en todos los idiomas
    private static final Set<String> GREENHOUSE_KEYWORDS = createGreenhouseKeywords();

    // Palabras de "luz"/"light" en todos los idiomas (SIN DUPLICADOS)
    private static final Set<String> LIGHT_WORDS = createLightWords();

    public CropLoreService(AeternumSeasonsPlugin plugin, SeasonalCropConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.KEY_MARK = new NamespacedKey(plugin, "seasonal_crop_lore");
    }

    // Métodos para crear los Sets SIN DUPLICADOS
    private static Set<String> createSeasonPrefixes() {
        Set<String> set = new HashSet<>();
        set.add("crece en:");      // es_ES.yml
        set.add("grows in:");      // en_US.yml
        set.add("pousse en:");     // fr_FR.yml
        set.add("cresce in:");     // it_IT.yml
        set.add("wächst in:");     // de_DE.yml
        set.add("crece em:");      // pt_BR.yml
        set.add("растет в");       // ru_RU.yml (sin : al final)
        set.add("rośnie w");       // pl_PL.yml (sin : al final)
        set.add("tumbuh di");      // id_ID.yml
        set.add("phát triển");     // vi_VN.yml (sin "trong:")
        set.add("büyür");          // tr_TR.yml (sin : al final)
        return Collections.unmodifiableSet(set);
    }

    private static Set<String> createAnySeasonKeywords() {
        Set<String> set = new HashSet<>();
        set.add("cualquier estación");      // es_ES
        set.add("any season");              // en_US
        set.add("toute saison");            // fr_FR
        set.add("qualsiasi stagione");      // it_IT
        set.add("jeder Jahreszeit");        // de_DE
        set.add("qualquer estação");        // pt_BR
        set.add("любое время года");        // ru_RU
        set.add("każdej porze roku");       // pl_PL
        set.add("musim apa pun");           // id_ID
        set.add("bất kỳ mùa nào");         // vi_VN
        set.add("her mevsim");              // tr_TR
        return Collections.unmodifiableSet(set);
    }

    private static Set<String> createGreenhouseKeywords() {
        Set<String> set = new HashSet<>();
        set.add("invernadero");      // es_ES
        set.add("greenhouse");       // en_US
        set.add("serre");           // fr_FR
        set.add("serra");           // it_IT
        set.add("gewächshaus");     // de_DE
        set.add("estufa");          // pt_BR
        set.add("теплица");         // ru_RU
        set.add("szklarnia");       // pl_PL
        set.add("rumah kaca");      // id_ID
        set.add("nhà kính");        // vi_VN
        set.add("sera");             // tr_TR
        return Collections.unmodifiableSet(set);
    }

    private static Set<String> createLightWords() {
        Set<String> set = new HashSet<>();
        set.add("luz");             // es_ES y pt_BR (se añade una vez)
        set.add("light");           // en_US
        set.add("lumière");         // fr_FR
        set.add("luce");            // it_IT
        set.add("licht");           // de_DE
        set.add("свет");            // ru_RU
        set.add("światło");         // pl_PL
        set.add("cahaya");          // id_ID
        set.add("ánh sáng");        // vi_VN
        set.add("ışık");            // tr_TR
        return Collections.unmodifiableSet(set);
    }

    /** Aplica/refresh lore para este item si es plantable manejado. */
    public boolean apply(ItemStack stack, org.bukkit.entity.Player viewer) {
        if (stack == null || stack.getType().isAir()) return false;

        EnumSet<Season> seasons = seasonsForItem(stack.getType());
        if (seasons == null || seasons.isEmpty()) return false;

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;

        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();

        // LIMPIAR lore antiguo de manera EFECTIVA
        lore = cleanAllSeasonalLore(lore);

        // ---- Texto principal ----
        String growsIn;
        if (seasons.size() >= 4) {
            growsIn = trSafe(viewer, "crop.lore.any_season", "&7Crece en cualquier estación");
        } else {
            String joined = seasons.stream()
                    .map(s -> plugin.lang.tr(viewer, "season." + s.name()))
                    .collect(Collectors.joining(", "));
            growsIn = trSafe(viewer, "crop.lore.grows_in", "&7Crece en: {seasons}")
                    .replace("{seasons}", joined);
        }
        lore.add(growsIn);

        // ---- Nota de invernadero subterráneo / luz ----
        int reqLight = config.getRequiredLight();
        String lightLine = trSafe(viewer, "crop.lore.greenhouse_light",
                "&8Invernadero subterráneo: luz {light}+")
                .replace("{light}", String.valueOf(reqLight));
        lore.add(lightLine);

        meta.setLore(lore);
        stack.setItemMeta(meta);
        return true;
    }

    /** Limpia TODO el lore de seasons - VERSIÓN MÁS EFECTIVA */
    private List<String> cleanAllSeasonalLore(List<String> lore) {
        if (lore == null || lore.isEmpty()) return new ArrayList<>();

        List<String> cleaned = new ArrayList<>();

        for (String line : lore) {
            if (line == null) continue;

            String plain = ChatColor.stripColor(line).toLowerCase(Locale.ROOT);
            boolean shouldRemove = false;

            // 1. Verificar si empieza con "crece en:", "grows in:", etc.
            for (String prefix : SEASON_PREFIXES) {
                if (plain.startsWith(prefix.toLowerCase(Locale.ROOT)) ||
                        plain.contains(prefix.toLowerCase(Locale.ROOT))) {
                    shouldRemove = true;
                    break;
                }
            }

            // 2. Verificar si es línea de "crece en cualquier estación"
            if (!shouldRemove) {
                for (String phrase : ANY_SEASON_KEYWORDS) {
                    if (plain.contains(phrase.toLowerCase(Locale.ROOT))) {
                        shouldRemove = true;
                        break;
                    }
                }
            }

            // 3. Verificar palabras de invernadero
            if (!shouldRemove) {
                for (String word : GREENHOUSE_KEYWORDS) {
                    if (plain.contains(word.toLowerCase(Locale.ROOT))) {
                        shouldRemove = true;
                        break;
                    }
                }
            }

            // 4. Verificar patrón de "luz 12+" en cualquier idioma
            if (!shouldRemove) {
                // Buscar número seguido de +
                if (plain.matches(".*\\d+\\+.*")) {
                    // Verificar si también tiene palabras de "luz"
                    for (String lightWord : LIGHT_WORDS) {
                        if (plain.contains(lightWord.toLowerCase(Locale.ROOT))) {
                            shouldRemove = true;
                            break;
                        }
                    }
                }
            }

            if (!shouldRemove) {
                cleaned.add(line);
            }
        }

        return cleaned;
    }

    /** Traducción segura: si falta key, usa fallback. */
    private String trSafe(org.bukkit.entity.Player p, String key, String fallback) {
        String v = plugin.lang.tr(p, key);
        if (v == null || v.equals(key)) return ChatColor.translateAlternateColorCodes('&', fallback);
        return v;
    }

    /**
     * Convierte item -> crop bloque manejado en crops.yml.
     */
    private EnumSet<Season> seasonsForItem(Material itemType) {
        if (config.isManagedCrop(itemType)) return config.getAllowedSeasons(itemType);

        Material cropType = switch (itemType) {
            case WHEAT_SEEDS -> Material.WHEAT;
            case BEETROOT_SEEDS -> Material.BEETROOTS;
            case CARROT -> Material.CARROTS;
            case POTATO -> Material.POTATOES;
            case MELON_SEEDS -> Material.MELON_STEM;
            case PUMPKIN_SEEDS -> Material.PUMPKIN_STEM;
            case NETHER_WART -> Material.NETHER_WART;
            case COCOA_BEANS -> Material.COCOA;
            case SWEET_BERRIES -> Material.SWEET_BERRY_BUSH;
            case BAMBOO -> Material.BAMBOO;
            case SUGAR_CANE -> Material.SUGAR_CANE;
            case CACTUS -> Material.CACTUS;
            case KELP -> Material.KELP;
            default -> null;
        };

        if (cropType == null) return null;
        return config.getAllowedSeasons(cropType);
    }
}