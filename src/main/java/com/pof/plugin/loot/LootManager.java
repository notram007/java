package com.pof.plugin.loot;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Builds weighted loot pools (junk / common / useful / rare) from all
 * usable Materials and rolls random items for players during a game.
 */
public class LootManager {

    private static final Set<Material> BLACKLIST = EnumSet.of(
            Material.AIR, Material.CAVE_AIR, Material.VOID_AIR,
            Material.BEDROCK, Material.BARRIER, Material.COMMAND_BLOCK,
            Material.COMMAND_BLOCK_MINECART, Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK,
            Material.STRUCTURE_VOID, Material.JIGSAW
    );

    private static final Set<Material> RARE_ITEMS = EnumSet.of(
            Material.DIAMOND_SWORD, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_HELMET,
            Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS, Material.DIAMOND_AXE,
            Material.NETHERITE_INGOT, Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE,
            Material.TOTEM_OF_UNDYING, Material.ELYTRA, Material.TRIDENT
    );

    private static final Set<Material> USEFUL_HINTS = EnumSet.of(
            Material.IRON_SWORD, Material.IRON_CHESTPLATE, Material.IRON_HELMET,
            Material.IRON_LEGGINGS, Material.IRON_BOOTS, Material.BOW, Material.ARROW,
            Material.SHIELD, Material.GOLDEN_CARROT, Material.ENDER_PEARL
    );

    private final Random random = new Random();
    private final List<Material> junkPool = new ArrayList<>();
    private final List<Material> commonPool = new ArrayList<>();
    private final List<Material> usefulPool = new ArrayList<>();
    private final List<Material> rarePool = new ArrayList<>();

    public LootManager() {
        buildPools();
    }

    private void buildPools() {
        for (Material material : Material.values()) {
            if (!material.isItem() || material.isLegacy()) {
                continue;
            }
            if (BLACKLIST.contains(material)) {
                continue;
            }

            if (RARE_ITEMS.contains(material)) {
                rarePool.add(material);
            } else if (USEFUL_HINTS.contains(material)
                    || material.isEdible()
                    || material.name().endsWith("_SPAWN_EGG")
                    || material.name().contains("POTION")) {
                usefulPool.add(material);
            } else if (material.isBlock() && material.isSolid()) {
                commonPool.add(material);
            } else {
                junkPool.add(material);
            }
        }

        if (junkPool.isEmpty()) junkPool.add(Material.DIRT);
        if (commonPool.isEmpty()) commonPool.add(Material.STONE);
        if (usefulPool.isEmpty()) usefulPool.add(Material.BREAD);
        if (rarePool.isEmpty()) rarePool.add(Material.DIAMOND_SWORD);
    }

    public ItemStack rollItem() {
        Material material = rollMaterial();
        int amount = rollAmount(material);
        return new ItemStack(material, amount);
    }

    private Material rollMaterial() {
        int roll = random.nextInt(100);
        // 50% junk, 30% common, 15% useful, 5% rare
        if (roll < 50) {
            return pickRandom(junkPool);
        } else if (roll < 80) {
            return pickRandom(commonPool);
        } else if (roll < 95) {
            return pickRandom(usefulPool);
        } else {
            return pickRandom(rarePool);
        }
    }

    private int rollAmount(Material material) {
        if (rarePool.contains(material)) {
            return 1;
        }
        if (material.getMaxStackSize() <= 1) {
            return 1;
        }
        return 1 + random.nextInt(Math.min(8, material.getMaxStackSize()));
    }

    private Material pickRandom(List<Material> pool) {
        return pool.get(random.nextInt(pool.size()));
    }
}
