package de.tobias30.claims;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

final class Teleports implements Listener {
    private final ClaimsPlugin plugin;
    private record Pending(UUID token, Location start, BukkitTask task) {}
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    Teleports(ClaimsPlugin plugin) { this.plugin = plugin; }
    void home(Player p, UUID owner, String name) {
        if (!plugin.use(p) || !plugin.owns(p, owner)) { ClaimsPlugin.error(p, "Access denied."); return; }
        var home = plugin.data.home(owner, name);
        if (home == null) { ClaimsPlugin.error(p, "Home not found."); return; }
        World world = Bukkit.getWorld(home.world());
        if (world == null) { ClaimsPlugin.error(p, "This home's world is unavailable."); return; }
        Location destination = new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
        if (!plugin.allowed(p, destination)) { ClaimsPlugin.error(p, "You no longer have access to that claim."); return; }
        if (cooldown.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis()) { ClaimsPlugin.error(p, "Please wait before teleporting again."); return; }
        if (p.isInsideVehicle() || p.isDead()) { ClaimsPlugin.error(p, "Leave your vehicle before teleporting."); return; }
        cancel(p, false); p.closeInventory();
        UUID token = UUID.randomUUID();
        int delay = Math.max(0, Math.min(60, plugin.getConfig().getInt("teleport.warmup-seconds", 3)));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!valid(p, token)) return;
            world.getChunkAtAsync(destination).whenComplete((chunk, error) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                if (!valid(p, token)) return;
                if (error != null || !plugin.use(p) || !plugin.owns(p, owner) || !home.equals(plugin.data.home(owner, name)) || !plugin.allowed(p, destination) || !safe(destination) || p.isInsideVehicle() || p.isDead()) {
                    cancel(p, false); ClaimsPlugin.error(p, "Home is unsafe, changed, or unavailable. Teleport cancelled."); return;
                }
                pending.remove(p.getUniqueId());
                // Chunk is loaded; perform on main thread so final permission/safety checks cannot race a later task.
                if (p.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                    cooldown.put(p.getUniqueId(), System.currentTimeMillis() + Math.max(0, plugin.getConfig().getInt("teleport.cooldown-seconds", 5)) * 1000L);
                    ClaimsPlugin.message(p, "Welcome to " + name + "."); plugin.audit(p, owner, "visited home " + name);
                }
                });
            });
        }, delay * 20L);
        pending.put(p.getUniqueId(), new Pending(token, p.getLocation(), task));
        ClaimsPlugin.message(p, "Teleporting in " + delay + " seconds. Stay still.");
    }
    private boolean valid(Player p, UUID token) { return p.isOnline() && pending.containsKey(p.getUniqueId()) && pending.get(p.getUniqueId()).token().equals(token); }
    private boolean safe(Location l) {
        World w = l.getWorld();
        if (l.getY() < w.getMinHeight() + 1 || l.getY() > w.getMaxHeight() - 2 || !w.getWorldBorder().isInside(l)) return false;
        var feet = l.getBlock(); var head = feet.getRelative(0, 1, 0); var floor = feet.getRelative(0, -1, 0);
        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid() && floor.getType().isSolid()
            && !danger(feet.getType()) && !danger(head.getType()) && !danger(floor.getType());
    }
    private boolean danger(Material m) { return Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS, Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE).contains(m); }
    private void cancel(Player p, boolean tell) { var task = pending.remove(p.getUniqueId()); if (task != null) { task.task().cancel(); if (tell) ClaimsPlugin.error(p, "Teleport cancelled."); } }
    @EventHandler(ignoreCancelled = true) public void move(PlayerMoveEvent e) {
        var task = pending.get(e.getPlayer().getUniqueId());
        if (task != null && e.getTo() != null && (!task.start().getWorld().equals(e.getTo().getWorld()) || task.start().distanceSquared(e.getTo()) > 0.01)) cancel(e.getPlayer(), true);
    }
    @EventHandler(ignoreCancelled = true) public void damage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p) cancel(p, true); }
    @EventHandler public void quit(PlayerQuitEvent e) { cancel(e.getPlayer(), false); cooldown.remove(e.getPlayer().getUniqueId()); }
    void close() { pending.values().forEach(p -> p.task().cancel()); pending.clear(); }
}
