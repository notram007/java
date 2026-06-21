package com.pof.plugin.game;

import com.pof.plugin.PillarsOfFortune;
import com.pof.plugin.loot.LootManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds every Arena to its own independent GameManager, so each named
 * arena runs its own waiting room, countdown, and game loop in parallel.
 */
public class ArenaService {

    private final PillarsOfFortune plugin;
    private final ArenaManager arenaManager;
    private final LootManager lootManager;
    private final GameStateStore stateStore;
    private final Map<String, GameManager> gameManagers = new LinkedHashMap<>();

    public ArenaService(PillarsOfFortune plugin, ArenaManager arenaManager, LootManager lootManager, GameStateStore stateStore) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.lootManager = lootManager;
        this.stateStore = stateStore;
        for (Arena arena : arenaManager.getAll()) {
            gameManagers.put(arena.getName().toLowerCase(), new GameManager(plugin, arena, lootManager, stateStore));
        }
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public Arena createArena(String name) {
        Arena arena = arenaManager.create(name);
        gameManagers.put(name.toLowerCase(), new GameManager(plugin, arena, lootManager, stateStore));
        return arena;
    }

    public void removeArena(String name) {
        GameManager gm = gameManagers.remove(name.toLowerCase());
        if (gm != null) {
            gm.forceStop(plugin.getServer().getConsoleSender());
        }
        arenaManager.remove(name);
    }

    public GameManager getGameManager(String arenaName) {
        return gameManagers.get(arenaName.toLowerCase());
    }

    public java.util.Collection<GameManager> getAllGameManagers() {
        return gameManagers.values();
    }

    /**
     * Finds the arena/game a player is currently part of (waiting or alive), if any.
     */
    public GameManager findGameForPlayer(org.bukkit.entity.Player player) {
        for (GameManager gm : gameManagers.values()) {
            if (gm.isPlayerInGame(player)) {
                return gm;
            }
        }
        return null;
    }
}
