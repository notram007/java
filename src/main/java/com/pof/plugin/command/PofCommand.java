package com.pof.plugin.command;

import com.pof.plugin.PillarsOfFortune;
import com.pof.plugin.game.Arena;
import com.pof.plugin.game.ArenaService;
import com.pof.plugin.game.GameManager;
import com.pof.plugin.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

public class PofCommand implements CommandExecutor {

    private final PillarsOfFortune plugin;

    public PofCommand(PillarsOfFortune plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        MessageUtil messages = plugin.getMessages();

        if (args.length == 0) {
            sender.sendMessage(messages.getPrefix() + "Usage: /pof <arena|setpillar|removepillar|liststate|forcestart|stop|join|leave|reload>");
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "arena":
                handleArena(sender, args);
                return true;
            case "setpillar":
                requireArenaArg(sender, args, this::handleSetPillar);
                return true;
            case "removepillar":
                requireArenaArg(sender, args, this::handleRemovePillar);
                return true;
            case "liststate":
                requireArenaArg(sender, args, this::handleListState);
                return true;
            case "forcestart":
                requireArenaArg(sender, args, this::handleForceStart);
                return true;
            case "stop":
                requireArenaArg(sender, args, this::handleStop);
                return true;
            case "join":
                requireArenaArg(sender, args, this::handleJoin);
                return true;
            case "leave":
                handleLeave(sender);
                return true;
            case "reload":
                handleReload(sender);
                return true;
            default:
                sender.sendMessage(messages.getPrefix() + "Unknown subcommand. Usage: /pof <arena|setpillar|removepillar|liststate|forcestart|stop|join|leave|reload>");
                return true;
        }
    }

    private interface ArenaAction {
        void run(CommandSender sender, String arenaName, String[] args);
    }

    private void requireArenaArg(CommandSender sender, String[] args, ArenaAction action) {
        MessageUtil messages = plugin.getMessages();
        if (args.length < 2) {
            sender.sendMessage(messages.getPrefix() + "Usage: /pof " + args[0] + " <arenaName>");
            return;
        }
        action.run(sender, args[1], args);
    }

    // ---------------- ARENA SUBCOMMAND TREE ----------------

    private void handleArena(CommandSender sender, String[] args) {
        MessageUtil messages = plugin.getMessages();
        if (!sender.hasPermission("pof.admin")) {
            sender.sendMessage(messages.getPrefix() + "You don't have permission to manage arenas.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(messages.getPrefix() + "Usage: /pof arena <create|remove|list|setlobby|setmapcenter|setregion> [name]");
            return;
        }

        String action = args[1].toLowerCase();
        ArenaService arenaService = plugin.getArenaService();

        switch (action) {
            case "create": {
                if (args.length < 3) {
                    sender.sendMessage(messages.getPrefix() + "Usage: /pof arena create <name>");
                    return;
                }
                String name = args[2];
                if (arenaService.getArenaManager().exists(name)) {
                    Map<String, String> ph = new HashMap<>();
                    ph.put("arena", name);
                    messages.send(sender, "arena-already-exists", ph);
                    return;
                }
                arenaService.createArena(name);
                Map<String, String> ph = new HashMap<>();
                ph.put("arena", name);
                messages.send(sender, "arena-created", ph);
                return;
            }
            case "remove": {
                if (args.length < 3) {
                    sender.sendMessage(messages.getPrefix() + "Usage: /pof arena remove <name>");
                    return;
                }
                String name = args[2];
                if (!arenaService.getArenaManager().exists(name)) {
                    Map<String, String> ph = new HashMap<>();
                    ph.put("arena", name);
                    messages.send(sender, "arena-not-found", ph);
                    return;
                }
                arenaService.removeArena(name);
                Map<String, String> ph = new HashMap<>();
                ph.put("arena", name);
                messages.send(sender, "arena-removed", ph);
                return;
            }
            case "list": {
                java.util.Collection<Arena> arenas = arenaService.getArenaManager().getAll();
                Map<String, String> headerPh = new HashMap<>();
                headerPh.put("count", String.valueOf(arenas.size()));
                sender.sendMessage(messages.format("arena-list-header", headerPh));
                int maxPlayers = plugin.getConfig().getInt("game.max-players", 16);
                for (Arena arena : arenas) {
                    GameManager gm = arenaService.getGameManager(arena.getName());
                    Map<String, String> ph = new HashMap<>();
                    ph.put("arena", arena.getName());
                    ph.put("state", gm != null ? gm.getState().name() : "UNKNOWN");
                    ph.put("current", String.valueOf(gm != null ? gm.getCurrentCount() : 0));
                    ph.put("max", String.valueOf(maxPlayers));
                    sender.sendMessage(messages.formatNoPrefix("arena-list-entry", ph));
                }
                return;
            }
            case "setlobby": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(messages.getPrefix() + "Only players can use this command.");
                    return;
                }
                if (args.length < 3) {
                    sender.sendMessage(messages.getPrefix() + "Usage: /pof arena setlobby <name>");
                    return;
                }
                String name = args[2];
                Arena arena = arenaService.getArenaManager().get(name);
                if (arena == null) {
                    Map<String, String> ph = new HashMap<>();
                    ph.put("arena", name);
                    messages.send(sender, "arena-not-found", ph);
                    return;
                }
                arena.setLobby(((Player) sender).getLocation());
                Map<String, String> ph = new HashMap<>();
                ph.put("arena", name);
                messages.send(sender, "arena-lobby-set", ph);
                return;
            }
            case "setmapcenter": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(messages.getPrefix() + "Only players can use this command.");
                    return;
                }
                if (args.length < 3) {
                    sender.sendMessage(messages.getPrefix() + "Usage: /pof arena setmapcenter <name>");
                    return;
                }
                String name = args[2];
                Arena arena = arenaService.getArenaManager().get(name);
                if (arena == null) {
                    Map<String, String> ph = new HashMap<>();
                    ph.put("arena", name);
                    messages.send(sender, "arena-not-found", ph);
                    return;
                }
                arena.setMapCenter(((Player) sender).getLocation());
                Map<String, String> ph = new HashMap<>();
                ph.put("arena", name);
                messages.send(sender, "arena-mapcenter-set", ph);
                return;
            }
            case "setregion": {
                if (!sender.hasPermission("pof.admin")) {
                    sender.sendMessage(messages.getPrefix() + "You don't have permission to manage arenas.");
                    return;
                }
                if (args.length < 4) {
                    sender.sendMessage(messages.getPrefix() + "Usage: /pof arena setregion <arena> <worldguardRegionName>");
                    return;
                }
                String arenaName = args[2];
                String regionName = args[3];
                Arena arena = arenaService.getArenaManager().get(arenaName);
                if (arena == null) {
                    Map<String, String> ph = new HashMap<>();
                    ph.put("arena", arenaName);
                    messages.send(sender, "arena-not-found", ph);
                    return;
                }
                arena.setRegionName(regionName);
                sender.sendMessage(messages.getPrefix() + "Arena " + arenaName + " will now use WorldGuard region: " + regionName);
                return;
            }
            default:
                sender.sendMessage(messages.getPrefix() + "Usage: /pof arena <create|remove|list|setlobby|setmapcenter|setregion> [name]");
        }
    }

    // ---------------- ADMIN PER-ARENA COMMANDS ----------------

    private void handleSetPillar(CommandSender sender, String arenaName, String[] args) {
        MessageUtil messages = plugin.getMessages();
        if (!sender.hasPermission("pof.admin")) {
            sender.sendMessage(messages.getPrefix() + "You don't have permission to manage arenas.");
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(messages.getPrefix() + "Only players can use this command.");
            return;
        }
        Arena arena = requireArena(sender, arenaName);
        if (arena == null) return;

        Player player = (Player) sender;
        arena.addPillar(player.getLocation());

        Map<String, String> ph = new HashMap<>();
        ph.put("arena", arenaName);
        ph.put("count", String.valueOf(arena.getPillarCount()));
        messages.send(sender, "pillar-set", ph);
    }

    private void handleRemovePillar(CommandSender sender, String arenaName, String[] args) {
        MessageUtil messages = plugin.getMessages();
        if (!sender.hasPermission("pof.admin")) {
            sender.sendMessage(messages.getPrefix() + "You don't have permission to manage arenas.");
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(messages.getPrefix() + "Only players can use this command.");
            return;
        }
        Arena arena = requireArena(sender, arenaName);
        if (arena == null) return;

        Player player = (Player) sender;
        arena.removeClosestPillar(player.getLocation());

        Map<String, String> ph = new HashMap<>();
        ph.put("arena", arenaName);
        ph.put("count", String.valueOf(arena.getPillarCount()));
        messages.send(sender, "pillar-removed", ph);
    }

    private void handleListState(CommandSender sender, String arenaName, String[] args) {
        Arena arena = requireArena(sender, arenaName);
        if (arena == null) return;
        GameManager gm = plugin.getArenaService().getGameManager(arenaName);

        sender.sendMessage(plugin.getMessages().getPrefix() + "Arena: " + arena.getName());
        sender.sendMessage(plugin.getMessages().getPrefix() + "State: " + (gm != null ? gm.getState() : "UNKNOWN"));
        sender.sendMessage(plugin.getMessages().getPrefix() + "Players: " + (gm != null ? gm.getCurrentCount() : 0));
        sender.sendMessage(plugin.getMessages().getPrefix() + "Registered pillars: " + arena.getPillarCount());
    }

    private void handleForceStart(CommandSender sender, String arenaName, String[] args) {
        MessageUtil messages = plugin.getMessages();
        if (!sender.hasPermission("pof.admin")) {
            sender.sendMessage(messages.getPrefix() + "You don't have permission to manage arenas.");
            return;
        }
        Arena arena = requireArena(sender, arenaName);
        if (arena == null) return;
        GameManager gm = plugin.getArenaService().getGameManager(arenaName);
        if (gm != null) {
            gm.forceStartFromAdmin(sender);
        }
    }

    private void handleStop(CommandSender sender, String arenaName, String[] args) {
        MessageUtil messages = plugin.getMessages();
        if (!sender.hasPermission("pof.admin")) {
            sender.sendMessage(messages.getPrefix() + "You don't have permission to manage arenas.");
            return;
        }
        Arena arena = requireArena(sender, arenaName);
        if (arena == null) return;
        GameManager gm = plugin.getArenaService().getGameManager(arenaName);
        if (gm != null) {
            gm.forceStop(sender);
        }
    }

    // ---------------- PLAYER COMMANDS ----------------

    private void handleJoin(CommandSender sender, String arenaName, String[] args) {
        MessageUtil messages = plugin.getMessages();
        if (!(sender instanceof Player)) {
            sender.sendMessage(messages.getPrefix() + "Only players can use this command.");
            return;
        }
        Arena arena = requireArena(sender, arenaName);
        if (arena == null) return;
        GameManager gm = plugin.getArenaService().getGameManager(arenaName);
        if (gm != null) {
            gm.joinGame((Player) sender);
        }
    }

    private void handleLeave(CommandSender sender) {
        MessageUtil messages = plugin.getMessages();
        if (!(sender instanceof Player)) {
            sender.sendMessage(messages.getPrefix() + "Only players can use this command.");
            return;
        }
        Player player = (Player) sender;
        GameManager gm = plugin.getArenaService().findGameForPlayer(player);
        if (gm == null) {
            messages.send(sender, "not-in-any-game");
            return;
        }
        gm.leaveGame(player);
    }

    private void handleReload(CommandSender sender) {
        MessageUtil messages = plugin.getMessages();
        if (!sender.hasPermission("pof.admin")) {
            sender.sendMessage(messages.getPrefix() + "You don't have permission to manage arenas.");
            return;
        }
        plugin.reloadConfig();
        plugin.reloadMessages();
        plugin.getLootManager().reload();
        sender.sendMessage(messages.getPrefix() + "Configuration reloaded (loot table refreshed).");
    }

    // ---------------- HELPERS ----------------

    private Arena requireArena(CommandSender sender, String arenaName) {
        Arena arena = plugin.getArenaService().getArenaManager().get(arenaName);
        if (arena == null) {
            Map<String, String> ph = new HashMap<>();
            ph.put("arena", arenaName);
            plugin.getMessages().send(sender, "arena-not-found", ph);
        }
        return arena;
    }
}
