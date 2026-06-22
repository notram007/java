package com.pof.plugin.listener;

import com.pof.plugin.PillarsOfFortune;
import com.pof.plugin.game.GameManager;
import com.pof.plugin.game.GameState;
import com.pof.plugin.util.WorldGuardHelper;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * During a live game:
 *  - Players CAN place block-type loot items inside the region
 *  - Players CAN break blocks THEY placed during the game
 *  - Players CANNOT break original map blocks
 *  - Region regen at game end restores everything anyway
 */
public class BlockListener implements Listener {

    private final PillarsOfFortune plugin;

    /**
     * Tracks every block location placed by a player during a running game.
     * Stored as "world:x:y:z" strings. Cleared when the game ends via clearPlacedBlocks().
     */
    private final Set<String> playerPlacedBlocks = Collections.synchronizedSet(new HashSet<>());

    public BlockListener(PillarsOfFortune plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ //
    //  BLOCK PLACE — allow inside region during a live game
    // ------------------------------------------------------------------ //

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        GameManager gm = plugin.getArenaService().findGameForPlayer(player);
        if (gm == null || gm.getState() != GameState.RUNNING) return;

        String regionName = gm.getArena().getRegionName();
        if (regionName == null || regionName.isEmpty()) return;

        if (!WorldGuardHelper.isLocationInRegion(event.getBlock().getLocation(), regionName)) return;

        Material mat = event.getItemInHand().getType();
        if (!mat.isBlock()) return;

        // Allow placement and remember this block was player-placed
        event.setCancelled(false);
        event.setBuild(true);
        playerPlacedBlocks.add(locationKey(event.getBlock().getLocation()));
    }

    // ------------------------------------------------------------------ //
    //  BLOCK BREAK — allow only player-placed blocks, deny original map
    // ------------------------------------------------------------------ //

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();

        GameManager gm = plugin.getArenaService().findGameForPlayer(player);
        if (gm == null || gm.getState() != GameState.RUNNING) return;

        String regionName = gm.getArena().getRegionName();
        if (regionName == null || regionName.isEmpty()) return;

        if (!WorldGuardHelper.isLocationInRegion(event.getBlock().getLocation(), regionName)) return;

        String key = locationKey(event.getBlock().getLocation());

        if (playerPlacedBlocks.contains(key)) {
            // This block was placed by a player during the game — allow breaking it
            event.setCancelled(false);
            event.setDropItems(false); // no drops — region regen handles cleanup
            playerPlacedBlocks.remove(key);
        } else {
            // Original map block — deny
            event.setCancelled(true);
            player.sendMessage(plugin.getMessages().getPrefix()
                    + "§cYou can only break blocks you placed!");
        }
    }

    // ------------------------------------------------------------------ //
    //  HELPERS
    // ------------------------------------------------------------------ //

    private String locationKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    /** Called by GameManager when the game ends so the set doesn't grow forever. */
    public void clearPlacedBlocks() {
        playerPlacedBlocks.clear();
    }
}
