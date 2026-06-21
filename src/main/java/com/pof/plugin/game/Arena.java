package com.pof.plugin.game;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents a single, independent Pillars of Fortune arena: its own
 * lobby, map center, pillars, join signs, and live game state. Multiple
 * Arenas can run simultaneously and independently of each other.
 */
public class Arena {

    private final String name;
    private final File file;
    private final Logger logger;

    private Location lobby;
    private Location mapCenter;
    private final List<Pillar> pillars = new ArrayList<>();
    private final List<Location> signLocations = new ArrayList<>();

    public Arena(String name, File dataFolder, Logger logger) {
        this.name = name;
        this.logger = logger;
        File arenasDir = new File(dataFolder, "arenas");
        if (!arenasDir.exists()) {
            arenasDir.mkdirs();
        }
        this.file = new File(arenasDir, name + ".yml");
        load();
    }

    public String getName() {
        return name;
    }

    public Location getLobby() {
        return lobby == null ? null : lobby.clone();
    }

    public void setLobby(Location lobby) {
        this.lobby = lobby.clone();
        save();
    }

    public Location getMapCenter() {
        if (mapCenter != null) {
            return mapCenter.clone();
        }
        // Default: average of all registered pillars
        if (pillars.isEmpty()) {
            return lobby == null ? null : lobby.clone();
        }
        double x = 0, y = 0, z = 0;
        for (Pillar p : pillars) {
            Location loc = p.getLocation();
            x += loc.getX();
            y += loc.getY();
            z += loc.getZ();
        }
        int count = pillars.size();
        Location first = pillars.get(0).getLocation();
        return new Location(first.getWorld(), x / count, y / count, z / count);
    }

    public void setMapCenter(Location mapCenter) {
        this.mapCenter = mapCenter.clone();
        save();
    }

    public List<Pillar> getPillars() {
        return Collections.unmodifiableList(pillars);
    }

    public void addPillar(Location location) {
        pillars.add(new Pillar(location));
        save();
    }

    public boolean removeClosestPillar(Location near) {
        Pillar closest = null;
        double closestDist = Double.MAX_VALUE;
        for (Pillar p : pillars) {
            Location loc = p.getLocation();
            if (loc.getWorld() == null || near.getWorld() == null) continue;
            if (!loc.getWorld().equals(near.getWorld())) continue;
            double dist = loc.distanceSquared(near);
            if (dist < closestDist) {
                closestDist = dist;
                closest = p;
            }
        }
        if (closest != null) {
            pillars.remove(closest);
            save();
            return true;
        }
        return false;
    }

    public int getPillarCount() {
        return pillars.size();
    }

    public List<Location> getSignLocations() {
        return Collections.unmodifiableList(signLocations);
    }

    public void registerSign(Location location) {
        signLocations.add(location.clone());
        save();
    }

    public boolean isJoinSign(Location location) {
        for (Location loc : signLocations) {
            if (loc.getWorld() != null && location.getWorld() != null
                    && loc.getWorld().equals(location.getWorld())
                    && loc.getBlockX() == location.getBlockX()
                    && loc.getBlockY() == location.getBlockY()
                    && loc.getBlockZ() == location.getBlockZ()) {
                return true;
            }
        }
        return false;
    }

    public void save() {
        try {
            YamlConfiguration config = new YamlConfiguration();

            if (lobby != null && lobby.getWorld() != null) {
                config.set("lobby.world", lobby.getWorld().getName());
                config.set("lobby.x", lobby.getX());
                config.set("lobby.y", lobby.getY());
                config.set("lobby.z", lobby.getZ());
                config.set("lobby.yaw", lobby.getYaw());
                config.set("lobby.pitch", lobby.getPitch());
            }

            if (mapCenter != null && mapCenter.getWorld() != null) {
                config.set("map-center.world", mapCenter.getWorld().getName());
                config.set("map-center.x", mapCenter.getX());
                config.set("map-center.y", mapCenter.getY());
                config.set("map-center.z", mapCenter.getZ());
            }

            for (int i = 0; i < pillars.size(); i++) {
                config.createSection("pillars." + i);
                pillars.get(i).saveTo(config.getConfigurationSection("pillars." + i));
            }

            for (int i = 0; i < signLocations.size(); i++) {
                Location loc = signLocations.get(i);
                String path = "signs." + i;
                config.set(path + ".world", loc.getWorld().getName());
                config.set(path + ".x", loc.getBlockX());
                config.set(path + ".y", loc.getBlockY());
                config.set(path + ".z", loc.getBlockZ());
            }

            config.save(file);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save arena file for " + name, e);
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        if (config.isConfigurationSection("lobby")) {
            String worldName = config.getString("lobby.world");
            org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
            if (world != null) {
                lobby = new Location(world,
                        config.getDouble("lobby.x"),
                        config.getDouble("lobby.y"),
                        config.getDouble("lobby.z"),
                        (float) config.getDouble("lobby.yaw"),
                        (float) config.getDouble("lobby.pitch"));
            } else {
                logger.warning("Skipping lobby with unknown world '" + worldName + "' in arena " + name);
            }
        }

        if (config.isConfigurationSection("map-center")) {
            String worldName = config.getString("map-center.world");
            org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
            if (world != null) {
                mapCenter = new Location(world,
                        config.getDouble("map-center.x"),
                        config.getDouble("map-center.y"),
                        config.getDouble("map-center.z"));
            }
        }

        if (config.isConfigurationSection("pillars")) {
            for (String key : config.getConfigurationSection("pillars").getKeys(false)) {
                Pillar pillar = Pillar.loadFrom(config.getConfigurationSection("pillars." + key));
                if (pillar != null) {
                    pillars.add(pillar);
                }
            }
        }

        if (config.isConfigurationSection("signs")) {
            for (String key : config.getConfigurationSection("signs").getKeys(false)) {
                String path = "signs." + key;
                String worldName = config.getString(path + ".world");
                org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
                if (world == null) {
                    logger.warning("Skipping sign with unknown world '" + worldName + "' in arena " + name);
                    continue;
                }
                int x = config.getInt(path + ".x");
                int y = config.getInt(path + ".y");
                int z = config.getInt(path + ".z");
                signLocations.add(new Location(world, x, y, z));
            }
        }
    }

    public void delete() {
        if (file.exists()) {
            file.delete();
        }
    }
}
