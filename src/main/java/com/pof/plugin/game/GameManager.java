package com.pof.plugin.game;

import com.pof.plugin.PillarsOfFortune;
import com.pof.plugin.loot.LootManager;
import com.pof.plugin.util.MessageUtil;
import com.pof.plugin.util.RegionSnapshotManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Monster;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Manages the live game lifecycle for a single Arena: waiting room,
 * countdown, loot ticking, off-map watching, duration timeout, and
 * win/elimination handling. Each Arena owns exactly one GameManager,
 * so multiple arenas run fully independently of one another.
 *
 * v2.0.4 changes:
 *  - Players are assigned a pillar and teleported there immediately on join
 *  - Players receive Slow Falling for 3 seconds so they safely land on the pillar
 *  - Players are frozen (cannot move) during WAITING and COUNTDOWN states
 *  - The game-start teleport is skipped (they are already standing on their pillar)
 */
public class GameManager {

    private final PillarsOfFortune plugin;
    private final Arena arena;
    private final LootManager lootManager;
    private final GameStateStore stateStore;
    private final MessageUtil messages;

    private GameState state = GameState.WAITING;

    // Players waiting in lobby / alive in game
    private final Set<Player> waitingPlayers = new LinkedHashSet<>();
    private final Set<Player> alivePlayers   = new LinkedHashSet<>();

    // Pillar assignments (set at join time, not at game start)
    private final Map<Player, Pillar> playerPillars       = new HashMap<>();
    // Pillars that have already been claimed so we never double-assign
    private final Set<Pillar>         claimedPillars      = new HashSet<>();

    private final Map<Player, ItemStack>          lastItemGiven    = new HashMap<>();
    private final Map<Player, InventorySnapshot>  savedInventories = new HashMap<>();

    // Players that must not be allowed to move (waiting / countdown phase)
    private final Set<UUID> frozenPlayers = new HashSet<>();

    // Players eliminated mid-game who are dead and awaiting Minecraft's respawn event.
    // We must NOT teleport/restore them until AFTER they respawn — doing it on a dead
    // player causes the stuck-respawn-screen bug and the ghost/desync bug.
    private final Set<UUID> pendingRespawn = new HashSet<>();

    // ------------------------------------------------------------------ //
    //  InventorySnapshot — full pre-game state so we can restore it later
    // ------------------------------------------------------------------ //

    private static class InventorySnapshot {
        final ItemStack[] contents;
        final ItemStack[] armorContents;
        final ItemStack   offHand;
        final GameMode    previousGameMode;
        final float       exp;
        final int         level;
        final double      health;
        final int         foodLevel;

        InventorySnapshot(Player player) {
            PlayerInventory inv = player.getInventory();
            this.contents        = inv.getContents().clone();
            this.armorContents   = inv.getArmorContents().clone();
            this.offHand         = inv.getItemInOffHand().clone();
            this.previousGameMode = player.getGameMode();
            this.exp             = player.getExp();
            this.level           = player.getLevel();
            this.health          = player.getHealth();
            this.foodLevel       = player.getFoodLevel();
        }

        void restore(Player player) {
            PlayerInventory inv = player.getInventory();
            inv.clear();
            inv.setContents(contents);
            inv.setArmorContents(armorContents);
            inv.setItemInOffHand(offHand);
            player.setExp(exp);
            player.setLevel(level);
            double maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH) != null
                    ? player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue()
                    : 20.0;
            player.setHealth(Math.min(health, maxHealth));
            player.setFoodLevel(foodLevel);
        }
    }

    // ------------------------------------------------------------------ //
    //  Tasks
    // ------------------------------------------------------------------ //

    private BukkitTask countdownTask;
    private BukkitTask lootTask;
    private BukkitTask durationTask;
    private BukkitTask offMapTask;

    // ------------------------------------------------------------------ //
    //  Constructor
    // ------------------------------------------------------------------ //

    public GameManager(PillarsOfFortune plugin, Arena arena, LootManager lootManager, GameStateStore stateStore) {
        this.plugin      = plugin;
        this.arena       = arena;
        this.lootManager = lootManager;
        this.stateStore  = stateStore;
        this.messages    = plugin.getMessages();
    }

    // ------------------------------------------------------------------ //
    //  Public getters
    // ------------------------------------------------------------------ //

    public Arena getArena() { return arena; }

    public GameState getState() { return state; }

    public int getCurrentCount() {
        return (state == GameState.WAITING || state == GameState.COUNTDOWN)
                ? waitingPlayers.size()
                : alivePlayers.size();
    }

    public boolean isPlayerInGame(Player player) {
        return waitingPlayers.contains(player) || alivePlayers.contains(player);
    }

    /** Called by the movement listener to decide whether to cancel a move event. */
    public boolean isPlayerFrozen(Player player) {
        return frozenPlayers.contains(player.getUniqueId());
    }

    private Map<String, String> arenaPlaceholder() {
        Map<String, String> map = new HashMap<>();
        map.put("arena", arena.getName());
        return map;
    }

    // ------------------------------------------------------------------ //
    //  JOIN / LEAVE
    // ------------------------------------------------------------------ //

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

        // We need a free pillar to give this player right now
        Pillar assignedPillar = pickFreePillar();
        if (assignedPillar == null) {
            Map<String, String> ph = arenaPlaceholder();
            ph.put("registered", String.valueOf(arena.getPillarCount()));
            messages.send(player, "not-enough-pillars", ph);
            return;
        }

        // --- Assign pillar, add to waiting set ---
        playerPillars.put(player, assignedPillar);
        claimedPillars.add(assignedPillar);
        waitingPlayers.add(player);
        stateStore.markInGame(player.getUniqueId());

        // Set adventure mode and freeze before teleport
        player.setGameMode(GameMode.ADVENTURE);
        freezePlayer(player);

        // Teleport straight to the pillar (not the lobby)
        player.teleport(assignedPillar.getLocation());

        // Give Slow Falling for 3 seconds so they float down safely
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOW_FALLING,
                60,   // 3 seconds = 60 ticks
                0,
                false,
                false,
                false
        ));

        // Broadcast join message
        Map<String, String> ph = arenaPlaceholder();
        ph.put("player", player.getName());
        ph.put("current", String.valueOf(waitingPlayers.size()));
        ph.put("max", String.valueOf(maxPlayers));
        broadcastToWaiting("join", ph);

        checkCountdownStart();
    }

    public void leaveGame(Player player) {
        boolean wasWaiting = waitingPlayers.remove(player);
        boolean wasAlive   = alivePlayers.remove(player);

        if (!wasWaiting && !wasAlive) {
            messages.send(player, "not-in-any-game", arenaPlaceholder());
            return;
        }

        // Release the pillar so another player can use it
        Pillar pillar = playerPillars.remove(player);
        if (pillar != null) claimedPillars.remove(pillar);

        unfreezePlayer(player);
        stateStore.markOutOfGame(player.getUniqueId());

        if (wasAlive || wasWaiting) {
            teleportToLobby(player);
            if (wasAlive) restorePlayer(player);
        }

        Map<String, String> ph = arenaPlaceholder();
        ph.put("player", player.getName());
        ph.put("current", String.valueOf(getCurrentCount()));
        ph.put("max", String.valueOf(plugin.getConfig().getInt("game.max-players", 16)));
        broadcastToWaiting("leave", ph);

        if (wasWaiting && state == GameState.COUNTDOWN
                && waitingPlayers.size() < plugin.getConfig().getInt("game.min-players", 2)) {
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

    // ------------------------------------------------------------------ //
    //  FREEZE / UNFREEZE
    // ------------------------------------------------------------------ //

    private void freezePlayer(Player player) {
        frozenPlayers.add(player.getUniqueId());
    }

    private void unfreezePlayer(Player player) {
        frozenPlayers.remove(player.getUniqueId());
    }

    private void unfreezeAll() {
        // Unfreeze everyone who was in the waiting set
        for (Player p : waitingPlayers) {
            frozenPlayers.remove(p.getUniqueId());
        }
        frozenPlayers.clear(); // belt-and-suspenders
    }

    // ------------------------------------------------------------------ //
    //  COUNTDOWN
    // ------------------------------------------------------------------ //

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
                if (state != GameState.COUNTDOWN) { cancel(); return; }
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
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        state = GameState.WAITING;
    }

    // ------------------------------------------------------------------ //
    //  GAME START
    // ------------------------------------------------------------------ //

    private void startGame() {
        state = GameState.RUNNING;

        // Snapshot the region BEFORE players place any blocks, so we can restore it after the game
        String regionName = arena.getRegionName();
        if (regionName != null && !regionName.isEmpty()) {
            org.bukkit.World world = getArenaWorld();
            if (world != null) {
                RegionSnapshotManager.snapshot(world, regionName);
            }
        }


        // Unfreeze everyone — they are already on their pillars
        unfreezeAll();

        for (Player player : waitingPlayers) {
            alivePlayers.add(player);

            // Save inventory BEFORE clearing it
            savedInventories.put(player, new InventorySnapshot(player));

            // Switch to survival and clear gear for the round
            player.setGameMode(GameMode.SURVIVAL);
            player.getInventory().clear();
            player.setExp(0);
            player.setLevel(0);
            player.setHealth(20.0);
            player.setFoodLevel(20);

            // Players are already on their assigned pillars from join time —
            // no teleport needed here.
        }
        waitingPlayers.clear();

        // Safety net: if only 1 player made it into alivePlayers, cancel immediately
        if (alivePlayers.size() < 2) {
            plugin.getLogger().warning("[PoF] Arena '" + arena.getName() + "' tried to start with only "
                    + alivePlayers.size() + " player(s) — cancelling and resetting to WAITING.");
            for (Player player : new ArrayList<>(alivePlayers)) {
                restorePlayer(player);
                teleportToLobby(player);
                player.sendMessage(plugin.getMessages().getPrefix()
                        + "§eNot enough players to start — waiting for more players to join.");
                stateStore.markOutOfGame(player.getUniqueId());
            }
            alivePlayers.clear();
            playerPillars.clear();
            claimedPillars.clear();
            savedInventories.clear();
            frozenPlayers.clear();
            state = GameState.WAITING;
            return;
        }

        int interval = plugin.getConfig().getInt("game.loot-interval-seconds", 5);
        Map<String, String> ph = arenaPlaceholder();
        ph.put("interval", String.valueOf(interval));
        broadcastToAlive("game-start", ph);

        startLootTicking();
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
        if (countdownTask != null) countdownTask.cancel();
        startGame();
    }

    // ------------------------------------------------------------------ //
    //  LOOT TICKING
    // ------------------------------------------------------------------ //

    private void startLootTicking() {
        int intervalTicks = plugin.getConfig().getInt("game.loot-interval-seconds", 5) * 20;

        plugin.getLogger().info("[PoF] Loot ticking started for arena '"
                + arena.getName() + "' — interval: " + (intervalTicks / 20) + "s.");

        lootTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != GameState.RUNNING) { cancel(); return; }
                // Read live from config every tick so /pof reload takes effect mid-game
                int itemsPerDrop = plugin.getConfig().getInt("game.items-per-drop", 1);
                for (Player player : new ArrayList<>(alivePlayers)) {
                    if (!player.isOnline()) continue;

                    for (int i = 0; i < itemsPerDrop; i++) {
                        ItemStack item = lootManager.rollItem();

                        // Try to add to inventory; if full, drop the item at the player's feet
                        java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                        if (!leftover.isEmpty()) {
                            for (ItemStack dropped : leftover.values()) {
                                player.getWorld().dropItemNaturally(player.getLocation(), dropped);
                            }
                            player.sendMessage(plugin.getMessages().getPrefix()
                                    + "§eYour inventory is full! Loot dropped at your feet.");
                        } else {
                            lastItemGiven.put(player, item);
                            Map<String, String> ph = arenaPlaceholder();
                            ph.put("item", describeLastItem(player));
                            messages.send(player, "loot-received", ph);
                        }
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

    // ------------------------------------------------------------------ //
    //  DURATION TIMEOUT
    // ------------------------------------------------------------------ //

    private void startDurationTimer() {
        int maxDuration = plugin.getConfig().getInt("game.max-duration-seconds", 300);
        if (maxDuration <= 0) return;
        durationTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != GameState.RUNNING) { cancel(); return; }
                forceEndByTimeout();
            }
        }.runTaskLater(plugin, maxDuration * 20L);
    }

    private void forceEndByTimeout() {
        if (state != GameState.RUNNING) return;
        endGame(new ArrayList<>(alivePlayers));
    }

    // ------------------------------------------------------------------ //
    //  OFF-MAP / VOID WATCHER
    // ------------------------------------------------------------------ //

    private void startOffMapWatcher() {
        // Void check removed — players on a superflat/void map die naturally
        // and are eliminated via PlayerDeathEvent in GameListener.
        // This watcher only handles the WorldGuard region boundary (leaving the arena sideways).
        String regionName = arena.getRegionName();
        if (regionName == null || regionName.isEmpty()) return; // nothing to watch

        // 200 ticks = 10 seconds grace so players fully load onto pillars before checks begin
        final long graceDelayTicks = 200L;

        // Player must fail 3 consecutive checks (3 seconds) before being eliminated
        final int failsBeforeEliminate = 3;
        final Map<java.util.UUID, Integer> outOfRegionCount = new HashMap<>();

        offMapTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != GameState.RUNNING) { cancel(); return; }

                for (Player player : new ArrayList<>(alivePlayers)) {
                    if (!player.isOnline()) continue;

                    boolean insideRegion = com.pof.plugin.util.WorldGuardHelper.isPlayerInRegion(player, regionName);

                    if (insideRegion) {
                        // Back inside — reset fail counter
                        outOfRegionCount.remove(player.getUniqueId());
                    } else {
                        int fails = outOfRegionCount.getOrDefault(player.getUniqueId(), 0) + 1;
                        outOfRegionCount.put(player.getUniqueId(), fails);

                        if (fails == 1) {
                            player.sendMessage(plugin.getMessages().getPrefix()
                                    + "§eReturn to the arena! You will be eliminated in "
                                    + (failsBeforeEliminate - 1) + " seconds!");
                        } else if (fails == 2) {
                            player.sendMessage(plugin.getMessages().getPrefix()
                                    + "§cLast warning — get back inside now!");
                        } else if (fails >= failsBeforeEliminate) {
                            outOfRegionCount.remove(player.getUniqueId());
                            eliminatePlayer(player);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, graceDelayTicks, 20L);
    }

    // ------------------------------------------------------------------ //
    //  DAMAGE / FALL HANDLING
    // ------------------------------------------------------------------ //

    public boolean shouldCancelFallDamage() {
        return plugin.getConfig().getBoolean("game.cancel-fall-damage", true);
    }

    public void handleDeath(Player player) {
        // Guard against double elimination (region watcher + death event firing together)
        if (!alivePlayers.contains(player)) return;
        eliminatePlayer(player);
    }

    // ------------------------------------------------------------------ //
    //  ELIMINATION / WIN
    // ------------------------------------------------------------------ //

    private void eliminatePlayer(Player player) {
        // Remove from alivePlayers first — if already removed, this is a duplicate call, ignore it
        if (!alivePlayers.remove(player)) return;

        Pillar pillar = playerPillars.remove(player);
        if (pillar != null) claimedPillars.remove(pillar);
        stateStore.markOutOfGame(player.getUniqueId());

        messages.send(player, "eliminated", arenaPlaceholder());

        // If the player is currently dead (health == 0 / dead flag), we MUST NOT
        // teleport or restore them yet — Minecraft hasn't run its respawn logic yet,
        // so the teleport will desync (ghost bug) and they'll be stuck on the respawn
        // screen. Instead we park them in pendingRespawn; the GameListener will call
        // finishElimination() from onPlayerRespawn once they're safely back alive.
        if (player.isDead()) {
            pendingRespawn.add(player.getUniqueId());
            // Set the respawn location to the lobby now so vanilla respawn puts them
            // in roughly the right place even before we do our own teleport.
            Location lobby = arena.getLobby();
            if (lobby != null) player.setBedSpawnLocation(lobby, true);
        } else {
            // Player is alive (e.g. eliminated by region-watcher, not by death)
            // — safe to teleport and restore immediately.
            restorePlayer(player);
            teleportToLobby(player);
        }

        Map<String, String> ph = arenaPlaceholder();
        ph.put("player", player.getName());
        ph.put("remaining", String.valueOf(alivePlayers.size()));
        broadcastToAlive("player-eliminated-broadcast", ph);

        checkForWinner();
    }



    /** Called by GameListener after the player has respawned (is alive again). */
    public boolean consumePendingRespawn(Player player) {
        return pendingRespawn.remove(player.getUniqueId());
    }

    /** Completes the elimination after the player has respawned. */
    public void finishElimination(Player player) {
        restorePlayer(player);
        teleportToLobby(player);
    }

    private void checkForWinner() {
        if (state != GameState.RUNNING) return;
        if (alivePlayers.size() <= 1) {
            endGame(new ArrayList<>(alivePlayers));
        }
    }

    private void endGame(List<Player> winners) {
        state = GameState.ENDING;

        // Restore the region to its pre-game state and kill all mobs/animals inside
        String regionName = arena.getRegionName();
        if (regionName != null && !regionName.isEmpty()) {
            org.bukkit.World world = getArenaWorld();
            if (world != null) {
                killEntitiesInRegion(world, regionName);
                RegionSnapshotManager.restore(world, regionName, plugin);
            }
        }

        if (!winners.isEmpty()) {
            Player winner = winners.get(0);
            Map<String, String> ph = arenaPlaceholder();
            ph.put("player", winner.getName());
            messages.send(winner, "win", ph);
            broadcastToAlive("win-broadcast", ph);
        }

        for (Player player : new ArrayList<>(alivePlayers)) {
            stateStore.markOutOfGame(player.getUniqueId());
            teleportToLobby(player);
            restorePlayer(player);
        }

        alivePlayers.clear();
        waitingPlayers.clear();
        playerPillars.clear();
        claimedPillars.clear();
        lastItemGiven.clear();
        frozenPlayers.clear();
        pendingRespawn.clear();

        // Clear player-placed block tracking so the set never grows unbounded
        plugin.getBlockListener().clearPlacedBlocks();

        cleanupTasks();
        state = GameState.WAITING;
    }

    public void forceStop(CommandSender sender) {
        if (state == GameState.WAITING) {
            sender.sendMessage(messages.getPrefix() + messages.formatNoPrefix("no-game-running", arenaPlaceholder()));
            return;
        }

        // Restore region on force-stop too, and kill all mobs/animals inside
        String regionName = arena.getRegionName();
        if (regionName != null && !regionName.isEmpty()) {
            org.bukkit.World world = getArenaWorld();
            if (world != null) {
                killEntitiesInRegion(world, regionName);
                RegionSnapshotManager.restore(world, regionName, plugin);
            }
        }
        for (Player player : new ArrayList<>(alivePlayers)) {
            stateStore.markOutOfGame(player.getUniqueId());
            teleportToLobby(player);
            restorePlayer(player);
        }
        for (Player player : new ArrayList<>(waitingPlayers)) {
            stateStore.markOutOfGame(player.getUniqueId());
            teleportToLobby(player);
        }
        alivePlayers.clear();
        waitingPlayers.clear();
        playerPillars.clear();
        claimedPillars.clear();
        lastItemGiven.clear();
        frozenPlayers.clear();
        pendingRespawn.clear();
        plugin.getBlockListener().clearPlacedBlocks();
        cleanupTasks();
        state = GameState.WAITING;
    }

    private void cleanupTasks() {
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        if (lootTask      != null) { lootTask.cancel();      lootTask      = null; }
        if (durationTask  != null) { durationTask.cancel();  durationTask  = null; }
        if (offMapTask    != null) { offMapTask.cancel();     offMapTask    = null; }
    }

    // ------------------------------------------------------------------ //
    //  HELPERS
    // ------------------------------------------------------------------ //

    /**
     * Returns the first pillar in the arena's list that has not yet been
     * claimed by a waiting/alive player, or null if all are taken.
     */
    private Pillar pickFreePillar() {
        for (Pillar p : arena.getPillars()) {
            if (!claimedPillars.contains(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Restores a player's pre-game inventory, armor, offhand, XP, health and
     * food, then puts them in adventure mode for the lobby.
     */
    private void restorePlayer(Player player) {
        // Clear any loot gained during the game BEFORE restoring the pre-game snapshot
        // This ensures no game items leak into the player's real inventory
        player.getInventory().clear();
        player.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        player.getInventory().setItemInOffHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR));
        lastItemGiven.remove(player);

        InventorySnapshot snapshot = savedInventories.remove(player);
        if (snapshot != null) {
            snapshot.restore(player);
            player.setGameMode(
                    snapshot.previousGameMode == GameMode.SURVIVAL || snapshot.previousGameMode == null
                            ? GameMode.ADVENTURE
                            : snapshot.previousGameMode
            );
        } else {
            player.setGameMode(GameMode.ADVENTURE);
        }
    }

    private void teleportToLobby(Player player) {
        Location lobby = arena.getLobby();
        if (lobby != null) player.teleport(lobby);
    }

    private void broadcastToWaiting(String key, Map<String, String> placeholders) {
        String msg = messages.format(key, placeholders);
        for (Player p : waitingPlayers) p.sendMessage(msg);
    }

    private void broadcastToAlive(String key, Map<String, String> placeholders) {
        String msg = messages.format(key, placeholders);
        for (Player p : alivePlayers) p.sendMessage(msg);
    }

    /**
     * Removes all non-player living entities (animals, monsters, ambient mobs like bats)
     * that are inside the arena's WorldGuard region bounding box.
     * Called after game end so loot-spawned or wandered-in mobs don't persist.
     */
    private void killEntitiesInRegion(org.bukkit.World world, String regionName) {
        try {
            com.sk89q.worldguard.protection.managers.RegionManager rm =
                    com.sk89q.worldguard.WorldGuard.getInstance()
                            .getPlatform()
                            .getRegionContainer()
                            .get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world));
            if (rm == null) return;

            com.sk89q.worldguard.protection.regions.ProtectedRegion region = rm.getRegion(regionName);
            if (region == null) return;

            com.sk89q.worldedit.math.BlockVector3 min = region.getMinimumPoint();
            com.sk89q.worldedit.math.BlockVector3 max = region.getMaximumPoint();

            // Use a bounding box slightly larger than the region to catch edge cases
            org.bukkit.util.BoundingBox box = org.bukkit.util.BoundingBox.of(
                    new org.bukkit.Location(world, min.x(), min.y(), min.z()),
                    new org.bukkit.Location(world, max.x(), max.y(), max.z())
            );

            int killed = 0;
            for (Entity entity : world.getNearbyEntities(box)) {
                // Never touch players — only animals, monsters, ambient (bats etc.), and misc mobs
                if (entity instanceof Player) continue;
                if (entity instanceof Animals
                        || entity instanceof Monster
                        || entity instanceof Ambient
                        || entity.getType() == EntityType.ARMOR_STAND
                        || entity.getType() == EntityType.ITEM_FRAME) continue; // keep decorations

                // Kill everything else (animals, monsters, fish, squids, etc.)
                entity.remove();
                killed++;
            }

            // Second pass — specifically target animals and monsters too
            for (Entity entity : world.getNearbyEntities(box)) {
                if (entity instanceof Player) continue;
                if (entity instanceof Animals || entity instanceof Monster || entity instanceof Ambient) {
                    entity.remove();
                    killed++;
                }
            }

            if (killed > 0) {
                plugin.getLogger().info("[PoF] Removed " + killed + " entities from region '" + regionName + "' after game end.");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[PoF] Failed to kill entities in region '" + regionName + "': " + e.getMessage());
        }
    }

    /**
     * Returns the Bukkit world this arena lives in, derived from the lobby
     * location or the first registered pillar. Used for WorldEdit operations.
     */
    private org.bukkit.World getArenaWorld() {
        Location lobby = arena.getLobby();
        if (lobby != null && lobby.getWorld() != null) return lobby.getWorld();
        if (!arena.getPillars().isEmpty()) {
            Location pillarLoc = arena.getPillars().get(0).getLocation();
            if (pillarLoc.getWorld() != null) return pillarLoc.getWorld();
        }
        return null;
    }

    public void checkPendingCrashRecovery(Player player) {
        if (stateStore.isMarkedInGame(player.getUniqueId())) {
            stateStore.markOutOfGame(player.getUniqueId());
            player.setGameMode(GameMode.SURVIVAL);
        }
    }
}
