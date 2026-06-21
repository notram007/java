package com.pof.plugin.game;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Tracks all named arenas on the server, keyed by lowercase name.
 * Each arena's persistent data lives in its own file under
 * plugins/PillarsOfFortune/arenas/<name>.yml
 */
public class ArenaManager {

    private final File dataFolder;
    private final Logger logger;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    public ArenaManager(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        loadExistingArenas();
    }

    private void loadExistingArenas() {
        File arenasDir = new File(dataFolder, "arenas");
        if (!arenasDir.exists()) {
            return;
        }
        File[] files = arenasDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File f : files) {
            String name = f.getName().substring(0, f.getName().length() - 4);
            arenas.put(name.toLowerCase(), new Arena(name, dataFolder, logger));
        }
    }

    public boolean exists(String name) {
        return arenas.containsKey(name.toLowerCase());
    }

    public Arena get(String name) {
        return arenas.get(name.toLowerCase());
    }

    public Arena create(String name) {
        Arena arena = new Arena(name, dataFolder, logger);
        arenas.put(name.toLowerCase(), arena);
        arena.save();
        return arena;
    }

    public void remove(String name) {
        Arena arena = arenas.remove(name.toLowerCase());
        if (arena != null) {
            arena.delete();
        }
    }

    public Collection<Arena> getAll() {
        return arenas.values();
    }

    public int count() {
        return arenas.size();
    }
}
