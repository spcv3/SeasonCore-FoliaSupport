package Kinkin.aeternum.world;

import Kinkin.aeternum.AeternumSeasonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import com.tcoded.folialib.wrapper.task.WrappedTask;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class BiomeBackupStore {

    private static final int MAGIC = 0xAEB10B10; // firma simple
    private static final byte VERSION = 1;

    private final AeternumSeasonsPlugin plugin;
    private final Path root;

    // evita doble guardado / guardado concurrente
    private final Set<Long> saved = ConcurrentHashMap.newKeySet();
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();

    public BiomeBackupStore(AeternumSeasonsPlugin plugin) {
        this.plugin = plugin;
        this.root = plugin.getDataFolder().toPath().resolve("biome_backups");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            plugin.getLogger().warning("[BiomeBackup] No se pudo crear carpeta root: " + e.getMessage());
        }
    }

    /* =========================== SAVE =========================== */

    public void saveFirstTouch(Chunk ch, Biome[] originalGrid, int stepXZ, int stepY) {
        World w = ch.getWorld();
        int cx = ch.getX();
        int cz = ch.getZ();
        long k = key(w, cx, cz);

        Path file = chunkFile(w, cx, cz);

        // si ya existe o ya lo tenemos marcado, no guardamos
        if (saved.contains(k) || pending.contains(k) || Files.exists(file)) {
            saved.add(k);
            return;
        }

        pending.add(k);

        // copia defensiva
        Biome[] copy = Arrays.copyOf(originalGrid, originalGrid.length);
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();

        plugin.getScheduler().runAsync(task -> {
            try {
                writeBackup(file, copy, stepXZ, stepY, minY, maxY);
                saved.add(k);
            } catch (Throwable t) {
                plugin.getLogger().warning("[BiomeBackup] Error guardando " + file + ": " + t.getMessage());
            } finally {
                pending.remove(k);
            }
        });
    }

    private void writeBackup(Path file, Biome[] grid, int stepXZ, int stepY, int minY, int maxY) throws IOException {
        Files.createDirectories(file.getParent());

        // paleta por nombre
        Map<String, Integer> paletteMap = new LinkedHashMap<>();
        List<String> palette = new ArrayList<>();
        int[] indices = new int[grid.length];

        for (int i = 0; i < grid.length; i++) {
            String name = grid[i].name();
            Integer idx = paletteMap.get(name);
            if (idx == null) {
                idx = palette.size();
                palette.add(name);
                paletteMap.put(name, idx);
            }
            indices[i] = idx;
        }

        boolean useByte = palette.size() <= 255;

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)))) {

            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(stepXZ);
            out.writeByte(stepY);
            out.writeInt(minY);
            out.writeInt(maxY);

            out.writeInt(palette.size());
            for (String s : palette) {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                out.writeShort(b.length);
                out.write(b);
            }

            out.writeInt(indices.length);
            out.writeBoolean(useByte);

            if (useByte) {
                for (int v : indices) out.writeByte(v);
            } else {
                for (int v : indices) out.writeShort(v);
            }
        }
    }

    /* =========================== RESTORE =========================== */

    public void startRestoreAll(CommandSender sender, int budgetChunksPerTick) {
        // escaneo async de todos los backups
        plugin.getScheduler().runAsync(task -> {
            List<Path> files = new ArrayList<>();

            if (Files.exists(root)) {
                try (Stream<Path> st = Files.walk(root)) {
                    st.filter(p -> p.toString().endsWith(".bin")).forEach(files::add);
                } catch (IOException e) {
                    plugin.getLogger().warning("[BiomeBackup] Error leyendo backups: " + e.getMessage());
                }
            }

            plugin.getScheduler().runNextTick(next -> {
                if (files.isEmpty()) {
                    sender.sendMessage("§e[BiomeBackup] No hay backups para restaurar.");
                    return;
                }

                sender.sendMessage("§a[BiomeBackup] Restaurando " + files.size()
                        + " chunks... budget=" + budgetChunksPerTick + "/tick");

                RestoreTask restoreTask = new RestoreTask(files.iterator(), sender, Math.max(1, budgetChunksPerTick));
                plugin.getScheduler().runTimer(restoreTask::tick, 1L, 1L);
            });
        });
    }

    private final class RestoreTask {
        private final Iterator<Path> it;
        private final CommandSender sender;
        private final int budget;

        private int restored = 0;
        private int failed = 0;

        private RestoreTask(Iterator<Path> it, CommandSender sender, int budget) {
            this.it = it;
            this.sender = sender;
            this.budget = budget;
        }

        private void tick(WrappedTask task) {
            int doneThisTick = 0;

            while (doneThisTick < budget && it.hasNext()) {
                Path file = it.next();
                boolean ok = restoreOne(file);
                if (ok) {
                    restored++;
                    // borrar .bin restaurado
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        plugin.getLogger().warning("[BiomeBackup] No se pudo borrar " + file + ": " + e.getMessage());
                    }
                    it.remove();
                } else {
                    failed++;
                }
                doneThisTick++;
            }

            if (!it.hasNext()) {
                task.cancel();
                sender.sendMessage("§a[BiomeBackup] Restore terminado. OK=" + restored + ", FAIL=" + failed);
            }
        }

        private boolean restoreOne(Path file) {
            try {
                // carpeta padre = world uuid
                Path worldDir = file.getParent();
                if (worldDir == null) return false;
                UUID worldId = UUID.fromString(worldDir.getFileName().toString());

                World w = Bukkit.getWorld(worldId);
                if (w == null) {
                    plugin.getLogger().warning("[BiomeBackup] Mundo no cargado para " + file);
                    return false;
                }

                // filename: cx_cz.bin
                String name = file.getFileName().toString();
                int us = name.indexOf('_');
                int dot = name.lastIndexOf(".bin");
                if (us <= 0 || dot <= us) return false;

                int cx = Integer.parseInt(name.substring(0, us));
                int cz = Integer.parseInt(name.substring(us + 1, dot));

                BackupData data = readBackup(file);
                applyBackup(w, cx, cz, data);
                return true;

            } catch (Throwable t) {
                plugin.getLogger().warning("[BiomeBackup] Restore error " + file + ": " + t.getMessage());
                return false;
            }
        }
    }

    private static final class BackupData {
        final int stepXZ, stepY;
        final int minY, maxY;
        final String[] palette;
        final int[] indices;

        BackupData(int stepXZ, int stepY, int minY, int maxY, String[] palette, int[] indices) {
            this.stepXZ = stepXZ;
            this.stepY = stepY;
            this.minY = minY;
            this.maxY = maxY;
            this.palette = palette;
            this.indices = indices;
        }
    }

    private BackupData readBackup(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int magic = in.readInt();
            if (magic != MAGIC) throw new IOException("Bad magic");

            byte ver = in.readByte();
            if (ver != VERSION) throw new IOException("Bad version " + ver);

            int stepXZ = in.readUnsignedByte();
            int stepY = in.readUnsignedByte();
            int minY = in.readInt();
            int maxY = in.readInt();

            int paletteSize = in.readInt();
            String[] palette = new String[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                int len = in.readUnsignedShort();
                byte[] b = new byte[len];
                in.readFully(b);
                palette[i] = new String(b, StandardCharsets.UTF_8);
            }

            int n = in.readInt();
            boolean useByte = in.readBoolean();
            int[] idx = new int[n];

            if (useByte) {
                for (int i = 0; i < n; i++) idx[i] = in.readUnsignedByte();
            } else {
                for (int i = 0; i < n; i++) idx[i] = in.readUnsignedShort();
            }

            return new BackupData(stepXZ, stepY, minY, maxY, palette, idx);
        }
    }

    private void applyBackup(World w, int cx, int cz, BackupData data) {
        Chunk ch = w.getChunkAt(cx, cz); // asegura cargado

        int bx = cx << 4;
        int bz = cz << 4;

        int curMin = w.getMinHeight();
        int curMax = w.getMaxHeight();

        int i = 0;
        for (int x = 0; x < 16; x += data.stepXZ) {
            for (int z = 0; z < 16; z += data.stepXZ) {
                for (int y = data.minY; y < data.maxY; y += data.stepY) {
                    if (i >= data.indices.length) break;

                    int pi = data.indices[i++];
                    if (pi < 0 || pi >= data.palette.length) continue;

                    if (y < curMin || y >= curMax) continue; // fuera del rango actual

                    Biome b = safeBiome(data.palette[pi]);
                    w.setBiome(bx + x, y, bz + z, b);
                }
            }
        }

        w.refreshChunk(cx, cz);
    }

    private Biome safeBiome(String name) {
        try {
            return Biome.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("[BiomeBackup] Biome desconocido '" + name + "', usando PLAINS");
            return Biome.PLAINS;
        }
    }

    /* =========================== PATHS / KEY =========================== */

    private Path worldDir(World w) {
        return root.resolve(w.getUID().toString());
    }

    private Path chunkFile(World w, int cx, int cz) {
        return worldDir(w).resolve(cx + "_" + cz + ".bin");
    }

    private long key(World w, int cx, int cz) {
        long k = (((long) cx) & 0xffffffffL) << 32 | (((long) cz) & 0xffffffffL);
        return k ^ (w.getUID().getMostSignificantBits() ^ w.getUID().getLeastSignificantBits());
    }
}
