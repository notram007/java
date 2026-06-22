package com.pof.plugin.loot;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Reads the loot table from config.yml and rolls random items for players.
 *
 * Config structure:
 *   loot:
 *     junk:
 *       weight: 50
 *       items:
 *         - { material: DIRT, min-amount: 1, max-amount: 8 }
 *     common:
 *       weight: 30
 *       items: ...
 *     useful:
 *       weight: 15
 *       items: ...
 *     rare:
 *       weight: 5
 *       items: ...
 *
 * Weights are relative — they don't need to add up to 100.
 * Reload with /pof reload (plugin calls reload() on this class).
 */
public class LootManager {

    private final Plugin plugin;
    private final Logger logger;
    private final Random random = new Random();

    private static class LootEntry {
        final Material material;
        final int minAmount;
        final int maxAmount;

        LootEntry(Material material, int minAmount, int maxAmount) {
            this.material  = material;
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
        }
    }

    private static class LootTier {
        final int weight;
        final List<LootEntry> entries;

        LootTier(int weight, List<LootEntry> entries) {
            this.weight  = weight;
            this.entries = entries;
        }
    }

    private final List<LootTier> tiers = new ArrayList<>();
    private int totalWeight = 0;

    public LootManager(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        reload();
    }

    // ------------------------------------------------------------------ //
    //  LOAD / RELOAD
    // ------------------------------------------------------------------ //

    public void reload() {
        tiers.clear();
        totalWeight = 0;

        FileConfiguration config = plugin.getConfig();
        ConfigurationSection lootSection = config.getConfigurationSection("loot");

        if (lootSection == null) {
            logger.warning("[PoF] No 'loot' section found in config.yml — using emergency fallback loot.");
            addFallbackTier();
            return;
        }

        int tiersLoaded = 0;
        int itemsLoaded = 0;

        for (String tierName : lootSection.getKeys(false)) {
            ConfigurationSection tierSection = lootSection.getConfigurationSection(tierName);
            if (tierSection == null) continue;

            int weight = tierSection.getInt("weight", 10);
            if (weight <= 0) continue;

            List<LootEntry> entries = new ArrayList<>();
            List<?> itemList = tierSection.getList("items");
            if (itemList == null) continue;

            for (Object obj : itemList) {
                if (!(obj instanceof java.util.Map)) continue;
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> map = (java.util.Map<String, Object>) obj;

                String matName = String.valueOf(map.getOrDefault("material", "AIR")).toUpperCase();
                int minAmt = toInt(map.getOrDefault("min-amount", 1));
                int maxAmt = toInt(map.getOrDefault("max-amount", 1));

                Material mat = Material.matchMaterial(matName);
                if (mat == null || !mat.isItem()) {
                    logger.warning("[PoF] Unknown or non-item material in loot config: '" + matName + "' — skipping.");
                    continue;
                }

                entries.add(new LootEntry(mat, Math.max(1, minAmt), Math.max(minAmt, maxAmt)));
                itemsLoaded++;
            }

            if (!entries.isEmpty()) {
                tiers.add(new LootTier(weight, entries));
                totalWeight += weight;
                tiersLoaded++;
            }
        }

        if (tiers.isEmpty()) {
            logger.warning("[PoF] Loot table is empty after loading config — using emergency fallback.");
            addFallbackTier();
        } else {
            logger.info("[PoF] Loot table loaded: " + tiersLoaded + " tier(s), "
                    + itemsLoaded + " item(s), total weight: " + totalWeight);
        }
    }

    // ------------------------------------------------------------------ //
    //  ROLL
    // ------------------------------------------------------------------ //

    public ItemStack rollItem() {
        LootTier tier = rollTier();
        LootEntry entry = tier.entries.get(random.nextInt(tier.entries.size()));
        int amount = entry.minAmount + (entry.maxAmount > entry.minAmount
                ? random.nextInt(entry.maxAmount - entry.minAmount + 1)
                : 0);
        // Clamp to item's max stack size
        amount = Math.min(amount, entry.material.getMaxStackSize());
        return new ItemStack(entry.material, Math.max(1, amount));
    }

    private LootTier rollTier() {
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (LootTier tier : tiers) {
            cumulative += tier.weight;
            if (roll < cumulative) return tier;
        }
        return tiers.get(tiers.size() - 1); // fallback
    }

    // ------------------------------------------------------------------ //
    //  FALLBACK (if config has no loot section at all)
    // ------------------------------------------------------------------ //

    private void addFallbackTier() {
        List<LootEntry> fallback = new ArrayList<>();
        fallback.add(new LootEntry(Material.STONE_SWORD, 1, 1));
        fallback.add(new LootEntry(Material.OAK_PLANKS, 4, 16));
        fallback.add(new LootEntry(Material.BREAD, 2, 4));
        fallback.add(new LootEntry(Material.ARROW, 4, 8));
        tiers.add(new LootTier(100, fallback));
        totalWeight = 100;
    }

    // ------------------------------------------------------------------ //
    //  HELPERS
    // ------------------------------------------------------------------ //

    private static int toInt(Object obj) {
        if (obj instanceof Number) return ((Number) obj).intValue();
        try { return Integer.parseInt(String.valueOf(obj)); }
        catch (NumberFormatException e) { return 1; }
    }
}
