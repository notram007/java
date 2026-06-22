package com.pof.plugin.game;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks which players were marked as "in a game" so that if the server
 * crashes mid-game, they can be detected and cleaned up (e.g. gamemode
 * reset, returned to a lobby) the next time they join.
 */
public class GameStateStore {

    private final File file;
    private final Logger logger;
    private final Set<UUID> trackedPlayers = new HashSet<>();

    public GameStateStore(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "active-state.yml");
        this.logger = logger;
        load();
    }

    public void markInGame(UUID uuid) {
        trackedPlayers.add(uuid);
        save();
    }

    public void markOutOfGame(UUID uuid) {
        trackedPlayers.remove(uuid);
        save();
    }

    public boolean isMarkedInGame(UUID uuid) {
        return trackedPlayers.contains(uuid);
    }

    /**
     * Returns and clears the full set of stale (crash-recovered) players.
     */
    public Set<UUID> consumeStaleEntries() {
        Set<UUID> stale = new HashSet<>(trackedPlayers);
        trackedPlayers.clear();
        save();
        return stale;
    }

    public void clearAll() {
        trackedPlayers.clear();
        save();
    }

    private void save() {
        try {
            YamlConfiguration config = new YamlConfiguration();
            int i = 0;
            for (UUID uuid : trackedPlayers) {
                config.set("players." + i, uuid.toString());
                i++;
            }
            config.save(file);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save active-state.yml", e);
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (!config.isConfigurationSection("players")) {
            return;
        }
        for (String key : config.getConfigurationSection("players").getKeys(false)) {
            String raw = config.getString("players." + key);
            try {
                trackedPlayers.add(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                logger.warning("Skipped invalid UUID in active-state.yml: " + raw);
            }
        }
    }
}
