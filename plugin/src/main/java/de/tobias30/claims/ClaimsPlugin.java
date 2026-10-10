package de.tobias30.claims;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

public final class ClaimsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    DataStore data;
    Menus menus;
    Teleports teleports;
    @Override public void onEnable() {
        saveDefaultConfig();
        try { data = new DataStore(getDataFolder().toPath().resolve("claims.dat")); }
        catch (IOException e) {
            // Do not leave an apparently operational but unprotected server after storage corruption.
            getLogger().severe("Cannot read claims.dat. Stopping server to preserve claim protection: " + e);
            getServer().shutdown(); return;
        }
        menus = new Menus(this); teleports = new Teleports(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(menus, this);
        getServer().getPluginManager().registerEvents(teleports, this);
        getServer().getPluginManager().registerEvents(new Protection(this), this);
        for (String name : List.of("claim", "unclaim", "claims", "homes", "home", "sethome", "delhome")) {
            Objects.requireNonNull(getCommand(name)).setExecutor(this); getCommand(name).setTabCompleter(this);
        }
        getServer().getOnlinePlayers().forEach(this::remember);
    }
    @Override public void onDisable() { if (menus != null) menus.close(); if (teleports != null) teleports.close(); }
    @EventHandler public void join(PlayerJoinEvent e) { remember(e.getPlayer()); }
    private void remember(Player p) {
        if (!data.name(p.getUniqueId()).equals(p.getName())) save(p, s -> s.names.put(p.getUniqueId(), p.getName()));
    }
    boolean save(Player p, Consumer<DataStore.State> change) {
        try { data.mutate(change); return true; }
        catch (IOException e) { getLogger().severe("Could not save Claims data: " + e); error(p, "Storage unavailable. Nothing was changed."); return false; }
    }
    static DataStore.Key key(Location l) { return new DataStore.Key(l.getWorld().getUID(), l.getBlockX() >> 4, l.getBlockZ() >> 4); }
    boolean allowed(Player p, Location l) {
        var claim = data.claim(key(l));
        return claim == null || claim.owner().equals(p.getUniqueId()) || claim.trusted().contains(p.getUniqueId()) || p.hasPermission("claims.bypass");
    }
    boolean owns(Player p, UUID owner) { return p.getUniqueId().equals(owner) || p.hasPermission("claims.admin"); }
    boolean use(Player p) { if (p.hasPermission("claims.use")) return true; error(p, "You cannot use Claims."); return false; }
    boolean admin(Player p) { if (p.hasPermission("claims.admin")) return true; error(p, "Admin permission required."); return false; }
    int limit(Player p, String kind) { return Limits.of(p, kind, getConfig().getInt("default-" + kind)); }
    boolean enabledWorld(Player p) {
        if (!getConfig().getStringList("disabled-worlds").contains(p.getWorld().getName())) return true;
        error(p, "Claims and new homes are disabled in this world."); return false;
    }
    static void message(Player p, String text) { p.sendMessage(Component.text("Claims » ", NamedTextColor.AQUA).append(Component.text(text, NamedTextColor.GRAY))); }
    static void error(Player p, String text) { p.sendMessage(Component.text("Claims » " + text, NamedTextColor.RED)); }
    void claim(Player p) {
        if (!use(p) || !enabledWorld(p)) return;
        var key = key(p.getLocation());
        if (data.claim(key) != null) { error(p, "This chunk is already claimed."); return; }
        if (data.claims(p.getUniqueId()).size() >= limit(p, "claims")) { error(p, "Claim limit reached."); return; }
        if (save(p, s -> s.claims.put(key, new DataStore.Claim(key, p.getUniqueId(), Set.of())))) message(p, "Chunk " + key.x() + ", " + key.z() + " claimed. Explosion protection is active.");
    }
    void unclaim(Player p, DataStore.Key key, UUID expectedOwner) {
        if (!use(p)) return;
        var c = data.claim(key);
        if (c == null || !c.owner().equals(expectedOwner) || !owns(p, c.owner())) { error(p, "This claim changed or is not yours."); return; }
        if (save(p, s -> s.claims.remove(key))) { message(p, "Claim released."); audit(p, c.owner(), "released claim " + key); }
    }
    void trust(Player p, DataStore.Key key, String name, boolean add) {
        if (!use(p)) return;
        var c = data.claim(key); UUID target = data.player(name);
        if (c == null || !owns(p, c.owner())) { error(p, "You do not own this claim."); return; }
        if (target == null) { error(p, "Player unknown. They must join once, or use their UUID."); return; }
        if (target.equals(c.owner())) { error(p, "The owner already has access."); return; }
        var trusted = new HashSet<>(c.trusted()); if (add) trusted.add(target); else trusted.remove(target);
        if (save(p, s -> s.claims.put(key, new DataStore.Claim(key, c.owner(), trusted)))) message(p, add ? "Player trusted in this chunk." : "Trust removed in this chunk.");
    }
    void setHome(Player p, String raw) {
        if (!use(p) || !enabledWorld(p)) return;
        String name = raw.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,24}")) { error(p, "Use 1–24 letters, digits, _ or - for a home name."); return; }
        if (!allowed(p, p.getLocation())) { error(p, "You cannot set a home in someone else's claim."); return; }
        UUID id = p.getUniqueId();
        if (data.home(id, name) == null && data.homes(id).size() >= limit(p, "homes")) { error(p, "Home limit reached."); return; }
        Location l = p.getLocation(); var h = new DataStore.Home(id, name, l.getWorld().getUID(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        if (data.home(id, name) != null) {
            menus.confirm(p, "Replace home " + name + "?", () -> {
                if (!use(p) || !allowed(p, l)) return;
                if (data.home(id, name) == null && data.homes(id).size() >= limit(p, "homes")) { error(p, "Home limit reached."); return; }
                storeHome(p, h); menus.homes(p, id, 0);
            }, () -> menus.homes(p, id, 0));
        } else storeHome(p, h);
    }
    private void storeHome(Player p, DataStore.Home h) {
        if (save(p, s -> s.homes.computeIfAbsent(h.owner(), k -> new HashMap<>()).put(h.name(), h))) message(p, "Home '" + h.name() + "' saved.");
    }
    void deleteHome(Player p, UUID owner, String name) {
        if (!use(p) || !owns(p, owner)) { error(p, "Access denied."); return; }
        if (save(p, s -> s.homes.computeIfAbsent(owner, k -> new HashMap<>()).remove(name))) {
            message(p, "Home deleted."); audit(p, owner, "deleted home " + name);
        }
    }
    void audit(Player p, UUID owner, String action) { if (!p.getUniqueId().equals(owner)) getLogger().info("ADMIN " + p.getName() + " " + action + " owned by " + owner); }
    String world(UUID id) { World w = Bukkit.getWorld(id); return w == null ? "Unavailable world" : w.getName(); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Use these menus in game."); return true; }
        if (!use(p)) return true;
        switch (command.getName()) {
            case "claim" -> claim(p);
            case "unclaim" -> { var c = data.claim(key(p.getLocation())); if (c == null) error(p, "This chunk is not claimed."); else menus.release(p, c); }
            case "homes" -> menus.homes(p, p.getUniqueId(), 0);
            case "home" -> { if (args.length == 0) menus.homes(p, p.getUniqueId(), 0); else teleports.home(p, p.getUniqueId(), args[0].toLowerCase(Locale.ROOT)); }
            case "sethome" -> { if (args.length != 1) error(p, "Usage: /sethome <name>"); else setHome(p, args[0]); }
            case "delhome" -> { if (args.length != 1) error(p, "Usage: /delhome <name>"); else menus.delete(p, p.getUniqueId(), args[0].toLowerCase(Locale.ROOT)); }
            case "claims" -> {
                String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "admin" -> menus.admin(p, 0);
                    case "trust", "untrust" -> { if (args.length != 2) error(p, "Usage: /claims " + sub + " <player>"); else trust(p, key(p.getLocation()), args[1], sub.equals("trust")); }
                    case "chest" -> { menus.chest(p); menus.claims(p, p.getUniqueId(), 0); }
                    case "auto" -> { menus.auto(p); menus.claims(p, p.getUniqueId(), 0); }
                    case "reload" -> { if (admin(p)) { reloadConfig(); message(p, "Configuration reloaded."); } }
                    default -> menus.claims(p, p.getUniqueId(), 0);
                }
            }
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        if (!(s instanceof Player p)) return List.of();
        List<String> options = List.of();
        if (args.length == 1) {
            if (Set.of("home", "delhome").contains(c.getName())) options = data.homes(p.getUniqueId()).stream().map(DataStore.Home::name).toList();
            if (c.getName().equals("claims")) options = p.hasPermission("claims.admin") ? List.of("menu", "trust", "untrust", "chest", "auto", "admin", "reload") : List.of("menu", "trust", "untrust", "chest", "auto");
        } else if (args.length == 2 && c.getName().equals("claims") && Set.of("trust", "untrust").contains(args[0])) options = data.players().stream().map(data::name).toList();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
