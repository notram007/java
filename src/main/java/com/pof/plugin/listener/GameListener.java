package com.pof.plugin.listener;

import com.pof.plugin.PillarsOfFortune;
import com.pof.plugin.game.GameManager;
import com.pof.plugin.game.GameState;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class GameListener implements Listener {

    private final PillarsOfFortune plugin;

    public GameListener(PillarsOfFortune plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ //
    //  FREEZE — block XZ movement for waiting/countdown players
    // ------------------------------------------------------------------ //

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        GameManager gm = plugin.getArenaService().findGameForPlayer(player);
        if (gm == null) return;

        // Only freeze during pre-game phases
        GameState st = gm.getState();
        if (st != GameState.WAITING && st != GameState.COUNTDOWN) return;

        if (!gm.isPlayerFrozen(player)) return;

        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        // Allow looking around (yaw/pitch change) but cancel any XZ/Y movement
        if (from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ()) {

            // Snap back to the exact from-position but keep the new head rotation
            Location cancel = from.clone();
            cancel.setYaw(to.getYaw());
            cancel.setPitch(to.getPitch());
            event.setTo(cancel);
        }
    }

    // ------------------------------------------------------------------ //
    //  FALL DAMAGE — cancel during a live game if configured
    // ------------------------------------------------------------------ //

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();

        GameManager gm = plugin.getArenaService().findGameForPlayer(player);
        if (gm == null || gm.getState() != GameState.RUNNING) return;

        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && gm.shouldCancelFallDamage()) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ //
    //  DEATH
    // ------------------------------------------------------------------ //

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        GameManager gm = plugin.getArenaService().findGameForPlayer(player);
        if (gm == null || gm.getState() != GameState.RUNNING) return;

        // Always suppress drops/messages for game players
        event.getDrops().clear();
        event.setDeathMessage(null);
        event.setDroppedExp(0);

        // handleDeath internally checks if player is still in alivePlayers —
        // if the region watcher already eliminated them this is safely a no-op
        gm.handleDeath(player);
    }

    // ------------------------------------------------------------------ //
    //  RESPAWN — complete elimination AFTER Minecraft brings the player back
    // ------------------------------------------------------------------ //

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        // Check every arena — the player was removed from alivePlayers already,
        // so findGameForPlayer won't find them. We check pendingRespawn instead.
        for (GameManager gm : plugin.getArenaService().getAllGameManagers()) {
            if (gm.consumePendingRespawn(player)) {
                // Override Minecraft's chosen respawn location to the arena lobby
                Location lobby = gm.getArena().getLobby();
                if (lobby != null) event.setRespawnLocation(lobby);

                // Schedule the restore + teleport for 1 tick later so the client
                // has fully processed the respawn packet before we move them again.
                // Without the delay, the client sometimes desyncs (ghost bug).
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        gm.finishElimination(player);
                    }
                }, 1L);
                break;
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        GameManager gm = plugin.getArenaService().findGameForPlayer(player);
        if (gm != null) gm.handleQuit(player);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (GameManager gm : plugin.getArenaService().getAllGameManagers()) {
            gm.checkPendingCrashRecovery(player);
        }
    }
}
