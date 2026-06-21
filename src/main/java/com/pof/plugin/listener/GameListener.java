package com.pof.plugin.listener;

import com.pof.plugin.PillarsOfFortune;
import com.pof.plugin.game.GameManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class GameListener implements Listener {

    private final PillarsOfFortune plugin;

    public GameListener(PillarsOfFortune plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();

        GameManager gm = plugin.getArenaService().findGameForPlayer(player);
        if (gm == null || gm.getState() != com.pof.plugin.game.GameState.RUNNING) return;

        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && gm.shouldCancelFallDamage()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        GameManager gm = plugin.getArenaService().findGameForPlayer(player);
        if (gm == null || gm.getState() != com.pof.plugin.game.GameState.RUNNING) return;

        event.getDrops().clear();
        event.setDeathMessage(null);
        event.setDroppedExp(0);

        gm.handleDeath(player);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            GameManager gm = plugin.getArenaService().findGameForPlayer(player);
            // Player already eliminated by the time they respawn; nothing further needed here,
            // but kept as an extension point (e.g. forcing them back to a lobby location).
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        GameManager gm = plugin.getArenaService().findGameForPlayer(player);
        if (gm != null) {
            gm.handleQuit(player);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (GameManager gm : plugin.getArenaService().getAllGameManagers()) {
            gm.checkPendingCrashRecovery(player);
        }
    }
}
