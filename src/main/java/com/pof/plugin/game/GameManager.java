package com.pof.plugin.game;

import com.pof.plugin.PillarsOfFortune;
import com.pof.plugin.loot.LootManager;
import com.pof.plugin.util.MessageUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Manages the live game lifecycle for a single Arena: waiting room,
 * countdown, loot ticking, off-map watching, duration timeout, and
 * win/elimination handling. Each Arena owns exactly one GameManager,
 * so multiple arenas run fully independently of one another.
 */
public class GameManager {

    private final PillarsOfFortune plugin;
    private final Arena arena;
    private final LootManager lootManager;
    private final GameStateStore stateStore;
    private final MessageUtil messages;

    private GameState state = GameState.WAITING;
    private final Set<Player> waitingPlayers = new LinkedHashSet<>();
    private final Set<Player> alivePlayers = new LinkedHashSet<>();
    private final Map<Player, Pillar> playerPillars = new HashMap<>();
    private final Map<Player, ItemStack> lastItemGiven = new HashMap<>();

    private BukkitTask countdownTask;
    private BukkitTask lootTask;
    private BukkitTask durationTask;
    private BukkitTask offMapTask;

    public GameManager(PillarsOfFortune plugin, Arena arena, LootManager lootManager, GameStateStore stateStore) {
        this.plugin = plugin;
        this.arena = arena;
        this.lootManager = lootManager;
        this.stateStore = stateStore;
        this.messages = plugin.getMessages();
    }

    public Arena getArena() {
        return arena;
    }

    public GameState getState() {
        return state;
    }

    public int getCurrentCount() {
        return state == GameState.WAITING || state == GameState.COUNTDOWN
                ? waitingPlayers.size()
                : alivePlayers.size();
    }

    public boolean isPlayerInGame(Player player) {
        return waitingPlayers.contains(player) || alivePlayers.contains(player);
    }

    private Map<String, String> arenaPlaceholder() {
        Map<String, String> map = new HashMap<>();
        map.put("arena", arena.getName());
        return map;
    }

    // ---------------- JOIN / LEAVE ----------------

    public void joinGame(Player player) {
        if (isPlayerInGame(player)) {
            messages.send(player, "already-in-game", arenaPlaceholder());
            return;
        }

        if (state == GameState.RUNNING || state == GameState.ENDING) {
            messages.send(player, "game-already-running", arenaPlaceholder());
            return;
        }

        Location lobby = arena.getLobby();
        if (lobby == null) {
            messages.send(player, "arena-lobby-not-set", arenaPlaceholder());
            return;
        }

        FileConfiguration config = plugin.getConfig();
        int maxPlayers = config.getInt("game.max-players", 16);
        if (waitingPlayers.size() >= maxPlayers) {
            messages.send(player, "game-full", arenaPlaceholder());
            return;
        }

        int registered = arena.getPillarCount();
        if (registered < waitingPlayers.size() + 1) {
            Map<String, String> ph = arenaPlaceholder();
            ph.put("registered", String.valueOf(registered));
            messages.send(player, "not-enough-pillars", ph);
            return;
        }

        waitingPlayers.add(player);
        stateStore.markInGame(player.getUniqueId());
        teleportToLobby(player);
        player.setGameMode(GameMode.ADVENTURE);

        Map<String, String> ph = arenaPlaceholder();
        ph.put("player", player.getName());
        ph.put("current", String.valueOf(waitingPlayers.size()));
        ph.put("max", String.valueOf(maxPlayers));
        broadcastToWaiting("join", ph);

        checkCountdownStart();
    }

    public void leaveGame(Player player) {
        boolean wasWaiting = waitingPlayers.remove(player);
        boolean wasAlive = alivePlayers.remove(player);

        if (!wasWaiting && !wasAlive) {
            messages.send(player, "not-in-any-game", arenaPlaceholder());
            return;
        }

        playerPillars.remove(player);
        stateStore.markOutOfGame(player.getUniqueId());

        Map<String, String> ph = arenaPlaceholder();
        ph.put("player", player.getName());
        ph.put("current", String.valueOf(getCurrentCount()));
        ph.put("max", String.valueOf(plugin.getConfig().getInt("game.max-players", 16)));
        broadcastToWaiting("leave", ph);

        if (wasWaiting && state == GameState.COUNTDOWN && waitingPlayers.size() < plugin.getConfig().getInt("game.min-players", 2)) {
            cancelCountdown();
        }

        if (wasAlive && state == GameState.RUNNING) {
            checkForWinner();
        }
    }

    public void handleQuit(Player player) {
        if (isPlayerInGame(player)) {
            leaveGame(player);
        }
    }

    // ---------------- COUNTDOWN ----------------

    private void checkCountdownStart() {
        int minPlayers = plugin.getConfig().getInt("game.min-players", 2);
        if (state == GameState.WAITING && waitingPlayers.size() >= minPlayers) {
            startCountdown();
        }
    }

    private void startCountdown() {
        state = GameState.COUNTDOWN;
        int seconds = plugin.getConfig().getInt("game.countdown-seconds", 15);

        Map<String, String> ph = arenaPlaceholder();
        ph.put("seconds", String.valueOf(seconds));
        broadcastToWaiting("countdown-start", ph);

        countdownTask = new BukkitRunnable() {
            int remaining = seconds;

            @Override
            public void run() {
                if (state != GameState.COUNTDOWN) {
                    cancel();
                    return;
                }
                remaining--;
                if (remaining <= 0) {
                    cancel();
                    startGame();
                    return;
                }
                if (remaining <= 5 || remaining % 5 == 0) {
                    Map<String, String> tickPh = arenaPlaceholder();
                    tickPh.put("seconds", String.valueOf(remaining));
                    broadcastToWaiting("countdown-tick", tickPh);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        state = GameState.WAITING;
    }

    // ---------------- GAME START ----------------

    private void startGame() {
        state = GameState.RUNNING;

        java.util.List<Pillar> pillarList = new java.util.ArrayList<>(arena.getPillars());
        java.util.Collections.shuffle(pillarList);

        int i = 0;
        for (Player player : waitingPlayers) {
            if (i >= pillarList.size()) break;
            Pillar pillar = pillarList.get(i++);
            playerPillars.put(player, pillar);
            alivePlayers.add(player);
            player.teleport(pillar.getLocation());
            player.setGameMode(GameMode.SURVIVAL);
            player.getInventory().clear();
        }
        waitingPlayers.clear();

        int interval = plugin.getConfig().getInt("game.loot-interval-seconds", 5);
        Map<String, String> ph = arenaPlaceholder();
        ph.put("interval", String.valueOf(interval));
        broadcastToAlive("game-start", ph);

        startLootTicking(plugin.getConfig().getInt("game.items-per-drop", 1));
        startDurationTimer();
        startOffMapWatcher();
    }

    public void forceStartFromAdmin(CommandSender sender) {
        if (state == GameState.RUNNING || state == GameState.COUNTDOWN) {
            sender.sendMessage(messages.getPrefix() + "Cannot force start - a game/countdown is already active.");
            return;
        }
        if (waitingPlayers.isEmpty()) {
            sender.sendMessage(messages.getPrefix() + "No players are waiting to start.");
            return;
        }
        if (countdownTask != null) {
            countdownTask.cancel();
        }
        startGame();
    }

    // ---------------- LOOT TICKING ----------------

    private void startLootTicking(int itemsPerDrop) {
        int intervalTicks = plugin.getConfig().getInt("game.loot-interval-seconds", 5) * 20;

        lootTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != GameState.RUNNING) {
                    cancel();
                    return;
                }
                for (Player player : new java.util.ArrayList<>(alivePlayers)) {
                    if (!player.isOnline()) continue;
                    ItemStack[] drops = new ItemStack[itemsPerDrop];
                    ItemStack lastItem = null;
                    for (int i = 0; i < itemsPerDrop; i++) {
                        ItemStack item = lootManager.rollItem();
                        drops[i] = item;
                        lastItem = item;
                    }
                    player.getInventory().addItem(drops);
                    if (lastItem != null) {
                        lastItemGiven.put(player, lastItem);
                        Map<String, String> ph = arenaPlaceholder();
                        ph.put("item", describeLastItem(player));
                        messages.send(player, "loot-received", ph);
                    }
                }
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks);
    }

    private String describeLastItem(Player player) {
        ItemStack item = lastItemGiven.get(player);
        if (item == null) return "item";
        return item.getAmount() + "x " + item.getType().name();
    }

    // ---------------- DURATION TIMEOUT ----------------

    private void startDurationTimer() {
        int maxDuration = plugin.getConfig().getInt("game.max-duration-seconds", 300);
        if (maxDuration <= 0) {
            return;
        }
        durationTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != GameState.RUNNING) {
                    cancel();
                    return;
                }
                forceEndByTimeout();
            }
        }.runTaskLater(plugin, maxDuration * 20L);
    }

    private void forceEndByTimeout() {
        if (state != GameState.RUNNING) return;
        endGame(new java.util.ArrayList<>(alivePlayers));
    }

    // ---------------- OFF-MAP / VOID WATCHER ----------------

    private void startOffMapWatcher() {
        Location mapCenter = arena.getMapCenter();
        double radius = plugin.getConfig().getDouble("game.map-radius", 60.0);
        double radiusSquared = radius * radius;
        double voidY = plugin.getConfig().getDouble("game.void-y-level", -10);

        offMapTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != GameState.RUNNING) {
                    cancel();
                    return;
                }
                java.util.List<Player> snapshot = new java.util.ArrayList<>(alivePlayers);
                for (Player player : snapshot) {
                    if (!player.isOnline()) continue;
                    Location playerLoc = player.getLocation();

                    if (playerLoc.getY() < voidY) {
                        eliminatePlayerVoid(player);
                        continue;
                    }

                    if (mapCenter == null || mapCenter.getWorld() == null) continue;
                    boolean differentWorld = playerLoc.getWorld() == null
                            || !playerLoc.getWorld().equals(mapCenter.getWorld());
                    if (differentWorld) continue;

                    double dx = playerLoc.getX() - mapCenter.getX();
                    double dz = playerLoc.getZ() - mapCenter.getZ();
                    double distSquared = dx * dx + dz * dz;
                    if (distSquared > radiusSquared) {
                        eliminatePlayer(player);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // ---------------- DAMAGE / FALL HANDLING ----------------

    public boolean shouldCancelFallDamage() {
        return plugin.getConfig().getBoolean("game.cancel-fall-damage", true);
    }

    public void handleDeath(Player player) {
        if (!alivePlayers.contains(player)) return;
        eliminatePlayer(player);
    }

    // ---------------- ELIMINATION / WIN ----------------

    private void eliminatePlayer(Player player) {
        if (!alivePlayers.remove(player)) return;
        playerPillars.remove(player);
        stateStore.markOutOfGame(player.getUniqueId());

        messages.send(player, "eliminated", arenaPlaceholder());
        teleportToLobby(player);
        player.setGameMode(GameMode.ADVENTURE);

        Map<String, String> ph = arenaPlaceholder();
        ph.put("player", player.getName());
        ph.put("remaining", String.valueOf(alivePlayers.size()));
        broadcastToAlive("player-eliminated-broadcast", ph);

        checkForWinner();
    }

    private void eliminatePlayerVoid(Player player) {
        if (!alivePlayers.remove(player)) return;
        playerPillars.remove(player);
        stateStore.markOutOfGame(player.getUniqueId());

        messages.send(player, "eliminated-void", arenaPlaceholder());
        teleportToLobby(player);
        player.setGameMode(GameMode.ADVENTURE);

        Map<String, String> ph = arenaPlaceholder();
        ph.put("player", player.getName());
        ph.put("remaining", String.valueOf(alivePlayers.size()));
        broadcastToAlive("player-eliminated-broadcast", ph);

        checkForWinner();
    }

    private void checkForWinner() {
        if (state != GameState.RUNNING) return;
        if (alivePlayers.size() <= 1) {
            endGame(new java.util.ArrayList<>(alivePlayers));
        }
    }

    private void endGame(java.util.List<Player> winners) {
        state = GameState.ENDING;

        if (!winners.isEmpty()) {
            Player winner = winners.get(0);
            Map<String, String> ph = arenaPlaceholder();
            ph.put("player", winner.getName());
            messages.send(winner, "win", ph);
            broadcastToAlive("win-broadcast", ph);
        }

        for (Player player : new java.util.ArrayList<>(alivePlayers)) {
            stateStore.markOutOfGame(player.getUniqueId());
            teleportToLobby(player);
            player.setGameMode(GameMode.ADVENTURE);
        }

        alivePlayers.clear();
        waitingPlayers.clear();
        playerPillars.clear();
        lastItemGiven.clear();

        cleanupTasks();
        state = GameState.WAITING;
    }

    public void forceStop(CommandSender sender) {
        if (state == GameState.WAITING) {
            sender.sendMessage(messages.getPrefix() + messages.formatNoPrefix("no-game-running", arenaPlaceholder()));
            return;
        }
        for (Player player : new java.util.ArrayList<>(alivePlayers)) {
            stateStore.markOutOfGame(player.getUniqueId());
            teleportToLobby(player);
            player.setGameMode(GameMode.ADVENTURE);
        }
        for (Player player : new java.util.ArrayList<>(waitingPlayers)) {
            stateStore.markOutOfGame(player.getUniqueId());
        }
        alivePlayers.clear();
        waitingPlayers.clear();
        playerPillars.clear();
        lastItemGiven.clear();
        cleanupTasks();
        state = GameState.WAITING;
    }

    private void cleanupTasks() {
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        if (lootTask != null) { lootTask.cancel(); lootTask = null; }
        if (durationTask != null) { durationTask.cancel(); durationTask = null; }
        if (offMapTask != null) { offMapTask.cancel(); offMapTask = null; }
    }

    // ---------------- HELPERS ----------------

    private void teleportToLobby(Player player) {
        Location lobby = arena.getLobby();
        if (lobby != null) {
            player.teleport(lobby);
        }
    }

    private void broadcastToWaiting(String key, Map<String, String> placeholders) {
        String msg = messages.format(key, placeholders);
        for (Player player : waitingPlayers) {
            player.sendMessage(msg);
        }
    }

    private void broadcastToAlive(String key, Map<String, String> placeholders) {
        String msg = messages.format(key, placeholders);
        for (Player player : alivePlayers) {
            player.sendMessage(msg);
        }
    }

    public void checkPendingCrashRecovery(Player player) {
        if (stateStore.isMarkedInGame(player.getUniqueId())) {
            stateStore.markOutOfGame(player.getUniqueId());
            player.setGameMode(GameMode.SURVIVAL);
        }
    }
}
