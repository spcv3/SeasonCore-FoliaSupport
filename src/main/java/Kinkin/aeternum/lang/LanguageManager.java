package Kinkin.aeternum.lang;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLocaleChangeEvent;

import java.io.File;
import java.util.*;

public final class LanguageManager implements Listener {

    private final AeternumSeasonsPlugin plugin;

    // LinkedHashSet para conservar el orden de "enabled" en lang.yml
    private final Set<String> enabled = new LinkedHashSet<>();
    private String def; // "auto" | <locale>
    private final Map<String, FileConfiguration> bundles = new HashMap<>();

    public LanguageManager(AeternumSeasonsPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        loadIndex();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void loadIndex() {
        // copiar lang.yml si no existe
        File idx = new File(plugin.getDataFolder(), "lang.yml");
        if (!idx.exists()) plugin.saveResource("lang.yml", false);

        FileConfiguration y = YamlConfiguration.loadConfiguration(idx);
        enabled.clear();
        enabled.addAll(y.getStringList("enabled"));
        def = y.getString("default", "auto");

        bundles.clear();
        for (String code : enabled) {
            File f = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
            if (!f.exists()) plugin.saveResource("lang/" + code + ".yml", false);
            bundles.put(code, YamlConfiguration.loadConfiguration(f));
        }
        // fallback mínimo
        bundles.computeIfAbsent("en_US", k -> {
            File f = new File(plugin.getDataFolder(), "lang/en_US.yml");
            if (!f.exists()) plugin.saveResource("lang/en_US.yml", false);
            return YamlConfiguration.loadConfiguration(f);
        });
    }

    /** Devuelve el código de idioma efectivo para un jugador, con fallback. */
    public String resolve(Player p) {
        if (p == null) {
            // Lógica para textos de servidor o items dropeados/loot (p=null).
            // Priorizamos EN_US sobre el 'default' configurado del servidor.
            if (enabled.contains("en_US")) return "en_US";
            // Si en_US no está habilitado, usa el primer idioma disponible.
            if (!enabled.isEmpty()) return enabled.iterator().next();
            return "en_US"; // Último recurso
        }

        String raw = p.getLocale();
        String wanted = safe(raw);

        // 1. Priorizar el idioma completo del jugador si está habilitado
        if (wanted != null && enabled.contains(wanted)) return wanted;

        // 2. Fallback por idioma base genérico (ej. 'es_MX' -> 'es_ES' si 'es_ES' está habilitado)
        if (wanted != null) {
            String lang = wanted.substring(0, 2).toLowerCase(Locale.ROOT);
            for (String code : enabled) {
                if (code.toLowerCase(Locale.ROOT).startsWith(lang + "_")) {
                    return code;
                }
            }
        }

        // 3. Si el idioma del jugador no está, usa el idioma 'default' del servidor como último recurso para ese jugador.
        if (!"auto".equalsIgnoreCase(def) && enabled.contains(def)) {
            return def;
        }

        // 4. Fallback final (al igual que en p=null)
        if (enabled.contains("en_US")) return "en_US";
        return enabled.stream().findFirst().orElse("en_US");
    }

    private String safe(String s) {
        if (s == null) return "en_US";

        String tmp = s.replace('-', '_'); // "id-id" -> "id_id"
        if (tmp.contains("_")) {
            String[] parts = tmp.split("_", 2);
            String lang = parts[0].toLowerCase(Locale.ROOT);
            String country = parts[1].toUpperCase(Locale.ROOT);
            return lang + "_" + country;   // "id_id" -> "id_ID"
        }
        return tmp.toLowerCase(Locale.ROOT);
    }

    /** season.SPRING → texto traducido (por jugador) */
    public String tr(Player p, String key) {
        String code = resolve(p);
        FileConfiguration b = bundles.getOrDefault(code, bundles.get("en_US"));

        String v = b.getString(key);

        // ✅ Si falta O está vacío → fallback a en_US
        if (v == null || v.isBlank()) {
            String en = bundles.get("en_US").getString(key);
            if (en != null && !en.isBlank()) v = en;
            else v = key; // último recurso
        }

        return ChatColor.translateAlternateColorCodes('&', v);
    }

    /**
     * Traducción en idioma del servidor (textos globales: nombres de mobs, mundos, etc.).
     * No depende del jugador ni de su locale.
     */
    public String trServer(String key) {
        String code = resolve(null);
        FileConfiguration b = bundles.getOrDefault(code, bundles.get("en_US"));

        String v = b.getString(key);

        // ✅ mismo fallback por vacío
        if (v == null || v.isBlank()) {
            String en = bundles.get("en_US").getString(key);
            if (en != null && !en.isBlank()) v = en;
            else v = key;
        }

        return ChatColor.translateAlternateColorCodes('&', v);
    }

    /**
     * Obtiene una lista de todas las traducciones posibles para una clave
     * en todos los idiomas habilitados.
     */
    public Set<String> getAllTranslations(String key) {
        Set<String> translations = new HashSet<>();
        // Recorre todos los bundles cargados, no solo los habilitados si hay un bug en loadIndex
        for (FileConfiguration b : bundles.values()) {
            String v = b.getString(key);
            if (v != null && !v.isBlank()) {
                // Añadir la versión con colores
                String coloredTag = ChatColor.translateAlternateColorCodes('&', v);
                translations.add(coloredTag);
                // Añadir la versión sin colores, para una limpieza más robusta (en caso de que el nombre se guarde sin color)
                translations.add(ChatColor.stripColor(coloredTag));
            }
        }
        return translations;
    }

    /** Replace {vars} en una string traducida. */
    public String trf(Player p, String key, Map<String, Object> vars) {
        String rawT = tr(p, key);
        if (vars == null || vars.isEmpty()) return rawT;
        String out = rawT;
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return out;
    }

    public void reload() {
        loadIndex();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // No necesita lógica aquí
    }

    @EventHandler
    public void onLocale(PlayerLocaleChangeEvent e) {
        // No necesita lógica aquí
    }
}