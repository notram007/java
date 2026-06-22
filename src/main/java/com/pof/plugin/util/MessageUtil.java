package com.pof.plugin.util;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;

public class MessageUtil {

    private final FileConfiguration config;

    public MessageUtil(FileConfiguration config) {
        this.config = config;
    }

    public String getPrefix() {
        return colorize(config.getString("messages.prefix", "&6[PoF] &r"));
    }

    public String format(String key, Map<String, String> placeholders) {
        String raw = config.getString("messages." + key, key);
        String result = raw;
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return colorize(getPrefix() + result);
    }

    public String formatNoPrefix(String key, Map<String, String> placeholders) {
        String raw = config.getString("messages." + key, key);
        String result = raw;
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return colorize(result);
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(format(key, placeholders));
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, null);
    }

    public String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}
