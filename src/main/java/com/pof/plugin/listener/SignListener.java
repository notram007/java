package com.pof.plugin.listener;

import com.pof.plugin.PillarsOfFortune;
import com.pof.plugin.game.Arena;
import com.pof.plugin.game.GameManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles join-sign creation and right-click-to-join.
 * Sign format (typed by the player when placing the sign):
 *   Line 1: [pof]
 *   Line 2: <arena name>
 *
 * On successful creation it's reformatted to:
 *   Line 1: [PoF]
 *   Line 2: <arena name>
 *   Line 3: Join Game
 */
public class SignListener implements Listener {

    private final PillarsOfFortune plugin;

    public SignListener(PillarsOfFortune plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        String firstLine = event.getLine(0);
        if (firstLine == null || !firstLine.trim().equalsIgnoreCase("[pof]")) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.hasPermission("pof.admin")) {
            player.sendMessage(plugin.getMessages().getPrefix() + "You don't have permission to create join signs.");
            return;
        }

        String arenaName = event.getLine(1) == null ? "" : event.getLine(1).trim();
        if (arenaName.isEmpty()) {
            player.sendMessage(plugin.getMessages().getPrefix()
                    + "Put the arena name on the second line of the sign, e.g. line 1 '[pof]', line 2 'lobby1'.");
            return;
        }

        Arena arena = plugin.getArenaService().getArenaManager().get(arenaName);
        if (arena == null) {
            player.sendMessage(plugin.getMessages().getPrefix() + "No arena named '" + arenaName + "' exists. Create it first with /pof arena create " + arenaName);
            return;
        }

        event.setLine(0, "[PoF]");
        event.setLine(1, arena.getName());
        event.setLine(2, "Join Game");

        arena.registerSign(event.getBlock().getLocation());

        Map<String, String> ph = new HashMap<>();
        ph.put("arena", arena.getName());
        plugin.getMessages().send(player, "sign-created", ph);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;

        Arena matchedArena = null;
        for (Arena arena : plugin.getArenaService().getArenaManager().getAll()) {
            if (arena.isJoinSign(event.getClickedBlock().getLocation())) {
                matchedArena = arena;
                break;
            }
        }
        if (matchedArena == null) return;

        event.setCancelled(true);
        GameManager gm = plugin.getArenaService().getGameManager(matchedArena.getName());
        if (gm != null) {
            gm.joinGame(event.getPlayer());
        }
    }
}
