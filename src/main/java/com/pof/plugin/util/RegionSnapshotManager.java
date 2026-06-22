package com.pof.plugin.util;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Snapshots every block inside a WorldGuard region using WorldEdit's API,
 * then restores them all when the game ends — giving you a full region regen
 * without needing a schematic file or Multiverse-NetherPortals.
 *
 * Usage:
 *   snapshot(world, regionName)  — call before the game starts
 *   restore(world, regionName, plugin)  — call after the game ends (async-safe via scheduler)
 */
public class RegionSnapshotManager {

    private static final Logger logger = Bukkit.getLogger();

    /**
     * arena-name (lowercase) -> flat map of BlockVector3 -> BlockState
     * We key by arena name so multiple arenas each keep their own snapshot.
     */
    private static final Map<String, Map<BlockVector3, BlockState>> snapshots = new HashMap<>();

    // ------------------------------------------------------------------ //
    //  SNAPSHOT
    // ------------------------------------------------------------------ //

    /**
     * Takes a full block snapshot of every block inside the WorldGuard region
     * assigned to this arena. Call this once, just before startGame() switches
     * players to SURVIVAL, so the region is still in its pristine state.
     *
     * @return true if the snapshot was taken successfully, false otherwise
     */
    public static boolean snapshot(World world, String regionName) {
        if (world == null || regionName == null || regionName.isEmpty()) return false;

        try {
            ProtectedRegion region = getRegion(world, regionName);
            if (region == null) {
                logger.warning("[PoF] RegionSnapshotManager: region '" + regionName + "' not found in world '" + world.getName() + "' — skipping snapshot.");
                return false;
            }

            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();

            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            Map<BlockVector3, BlockState> blockMap = new HashMap<>();

            // Walk every block in the bounding box and store its full BlockState
            // (includes block type + all properties like facing, waterlogged, etc.)
            for (int x = min.x(); x <= max.x(); x++) {
                for (int y = min.y(); y <= max.y(); y++) {
                    for (int z = min.z(); z <= max.z(); z++) {
                        BlockVector3 pos = BlockVector3.at(x, y, z);
                        // Only snapshot blocks that are actually inside the region
                        // (regions can be non-rectangular in some WG versions, but
                        //  for cuboid regions this is always true)
                        if (region.contains(pos)) {
                            BlockState state = weWorld.getBlock(pos);
                            blockMap.put(pos, state);
                        }
                    }
                }
            }

            snapshots.put(regionName.toLowerCase(), blockMap);
            logger.info("[PoF] Snapshot taken for region '" + regionName + "' — " + blockMap.size() + " blocks stored.");
            return true;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "[PoF] Failed to snapshot region '" + regionName + "'", e);
            return false;
        }
    }

    // ------------------------------------------------------------------ //
    //  RESTORE
    // ------------------------------------------------------------------ //

    /**
     * Restores every block in the snapshot back to its saved state.
     * Uses WorldEdit's EditSession so block physics / lighting are handled properly.
     * Runs synchronously on the main thread (called from endGame which is already sync).
     *
     * @param plugin needed for the async scheduler tick if you ever want to defer it
     */
    public static void restore(World world, String regionName, Plugin plugin) {
        if (world == null || regionName == null || regionName.isEmpty()) return;

        Map<BlockVector3, BlockState> blockMap = snapshots.get(regionName.toLowerCase());
        if (blockMap == null || blockMap.isEmpty()) {
            logger.warning("[PoF] No snapshot found for region '" + regionName + "' — cannot restore.");
            return;
        }

        // Run on next tick so players have already been teleported out before blocks start changing
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);

                try (EditSession editSession = WorldEdit.getInstance()
                        .newEditSessionBuilder()
                        .world(weWorld)
                        .maxBlocks(-1)
                        .build()) {

                    for (Map.Entry<BlockVector3, BlockState> entry : blockMap.entrySet()) {
                        editSession.setBlock(entry.getKey(), entry.getValue());
                    }
                    // EditSession auto-flushes on close (try-with-resources)
                }

                logger.info("[PoF] Region '" + regionName + "' restored — " + blockMap.size() + " blocks reset.");

            } catch (Exception e) {
                logger.log(Level.SEVERE, "[PoF] Failed to restore region '" + regionName + "'", e);
            }
        });
    }

    // ------------------------------------------------------------------ //
    //  HELPERS
    // ------------------------------------------------------------------ //

    private static ProtectedRegion getRegion(World world, String regionName) {
        try {
            RegionManager rm = WorldGuard.getInstance()
                    .getPlatform()
                    .getRegionContainer()
                    .get(BukkitAdapter.adapt(world));
            return rm == null ? null : rm.getRegion(regionName);
        } catch (Exception e) {
            return null;
        }
    }

    /** Clears the in-memory snapshot for an arena (e.g. when it's deleted). */
    public static void clearSnapshot(String regionName) {
        if (regionName != null) snapshots.remove(regionName.toLowerCase());
    }

    public static boolean hasSnapshot(String regionName) {
        if (regionName == null) return false;
        Map<BlockVector3, BlockState> m = snapshots.get(regionName.toLowerCase());
        return m != null && !m.isEmpty();
    }
}
