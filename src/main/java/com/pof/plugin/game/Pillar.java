package com.pof.plugin.game;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

public class Pillar {

    private final Location location;

    public Pillar(Location location) {
        this.location = location.clone();
    }

    public Location getLocation() {
        return location.clone();
    }

    public void saveTo(ConfigurationSection section) {
        section.set("world", location.getWorld().getName());
        section.set("x", location.getX());
        section.set("y", location.getY());
        section.set("z", location.getZ());
        section.set("yaw", location.getYaw());
        section.set("pitch", location.getPitch());
    }

    public static Pillar loadFrom(ConfigurationSection section) {
        String worldName = section.getString("world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        double x = section.getDouble("x");
        double y = section.getDouble("y");
        double z = section.getDouble("z");
        float yaw = (float) section.getDouble("yaw");
        float pitch = (float) section.getDouble("pitch");
        return new Pillar(new Location(world, x, y, z, yaw, pitch));
    }
}
