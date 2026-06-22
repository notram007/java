package com.pof.plugin.util;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility for checking if a player or location is inside a WorldGuard region.
 * Safely handles the case where WorldGuard is not installed or is disabled.
 */
public class WorldGuardHelper {

    private static final Logger logger = org.bukkit.Bukkit.getLogger();
    private static boolean worldGuardAvailable = false;

    static {
        try {
            Class.forName("com.sk89q.worldguard.WorldGuard");
            worldGuardAvailable = true;
        } catch (ClassNotFoundException e) {
            worldGuardAvailable = false;
        }
    }

    /** Check if a player's current position is inside a WorldGuard region. */
    public static boolean isPlayerInRegion(Player player, String regionName) {
        return isLocationInRegion(player.getLocation(), regionName);
    }

    /** Check if an arbitrary Location is inside a WorldGuard region. */
    public static boolean isLocationInRegion(Location location, String regionName) {
        if (!worldGuardAvailable || regionName == null || regionName.isEmpty()) return false;

        try {
            World world = location.getWorld();
            if (world == null) return false;

            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            // Use BukkitAdapter for world conversion to match WorldGuard 7.x API
            com.sk89q.worldedit.world.World weWorld =
                    com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world);
            RegionManager regionManager = container.get(weWorld);
            if (regionManager == null) return false;

            ProtectedRegion region = regionManager.getRegion(regionName);
            if (region == null) return false;

            BlockVector3 pos = BlockVector3.at(
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
            return region.contains(pos);

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error checking WorldGuard region: " + regionName, e);
            return false;
        }
    }

    public static boolean isWorldGuardAvailable() {
        return worldGuardAvailable;
    }
}
