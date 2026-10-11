package de.tobias30.claims;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.*;

final class Teleports implements Listener {
    private final ClaimsPlugin plugin;
    private record Pending(UUID token, Location start, BukkitTask task) {}
    private record Request(UUID id, UUID requester, UUID target, boolean here, BukkitTask expiry) {}
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Map<UUID, Request> incoming = new HashMap<>();
    private final Map<UUID, Request> outgoing = new HashMap<>();
    Teleports(ClaimsPlugin plugin) { this.plugin = plugin; }
    void home(Player p, UUID owner, String name) {
        if (!plugin.use(p) || !plugin.owns(p, owner)) { ClaimsPlugin.error(p, "Access denied."); return; }
        var home = plugin.data.home(owner, name);
        if (home == null) { ClaimsPlugin.error(p, "Home not found."); return; }
        World world = Bukkit.getWorld(home.world());
        if (world == null) { ClaimsPlugin.error(p, "This home's world is unavailable."); return; }
        Location destination = new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
        if (!plugin.allowed(p, destination)) { ClaimsPlugin.error(p, "You no longer have access to that claim."); return; }
        begin(p, destination, "Welcome to " + name + ".", () -> plugin.use(p) && plugin.owns(p, owner)
                && home.equals(plugin.data.home(owner, name)), () -> plugin.audit(p, owner, "visited home " + name));
    }

    void request(Player requester, Player target, boolean here) {
        if (requester.equals(target)) { ClaimsPlugin.error(requester, "You cannot send a teleport request to yourself."); return; }
        Request previousOutgoing = outgoing.remove(requester.getUniqueId());
        if (previousOutgoing != null) remove(previousOutgoing, false);
        Request previousIncoming = incoming.remove(target.getUniqueId());
        if (previousIncoming != null) remove(previousIncoming, true);
        UUID id = UUID.randomUUID();
        BukkitTask expiry = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Request current = incoming.get(target.getUniqueId());
            if (current != null && current.id().equals(id)) {
                remove(current, false);
                if (requester.isOnline()) ClaimsPlugin.error(requester, "Your teleport request expired.");
                if (target.isOnline()) ClaimsPlugin.error(target, "Teleport request expired.");
            }
        }, 60 * 20L);
        Request request = new Request(id, requester.getUniqueId(), target.getUniqueId(), here, expiry);
        incoming.put(target.getUniqueId(), request);
        outgoing.put(requester.getUniqueId(), request);
        ClaimsPlugin.message(requester, "Teleport request sent to " + target.getName() + ". It expires in 60 seconds.");
        target.sendMessage(Component.text(requester.getName() + (here ? " wants you to teleport to them. " : " wants to teleport to you. "), NamedTextColor.AQUA)
                .append(Component.text("[Accept]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/tpaccept")))
                .append(Component.text("  ", NamedTextColor.GRAY))
                .append(Component.text("[Deny]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/tpdeny"))));
    }

    void accept(Player target) {
        Request request = incoming.get(target.getUniqueId());
        if (request == null) { ClaimsPlugin.error(target, "You have no pending teleport request."); return; }
        Player requester = Bukkit.getPlayer(request.requester());
        if (requester == null) { remove(request, false); ClaimsPlugin.error(target, "That player is no longer online."); return; }
        remove(request, false);
        Player teleporter = request.here() ? target : requester;
        Player destinationPlayer = request.here() ? requester : target;
        begin(teleporter, destinationPlayer.getLocation().clone(), "Teleport request accepted.", teleporter::isOnline, () -> {});
        ClaimsPlugin.message(target, "Teleport request accepted.");
        if (teleporter != target) ClaimsPlugin.message(requester, "Your request was accepted.");
    }

    void deny(Player target) {
        Request request = incoming.get(target.getUniqueId());
        if (request == null) { ClaimsPlugin.error(target, "You have no pending teleport request."); return; }
        remove(request, false);
        ClaimsPlugin.message(target, "Teleport request denied.");
        Player requester = Bukkit.getPlayer(request.requester());
        if (requester != null) ClaimsPlugin.error(requester, target.getName() + " denied your teleport request.");
    }

    void cancelRequest(Player player) {
        Request request = outgoing.get(player.getUniqueId());
        if (request == null) return;
        remove(request, false);
        ClaimsPlugin.message(player, "Teleport request cancelled.");
        Player target = Bukkit.getPlayer(request.target());
        if (target != null) ClaimsPlugin.error(target, player.getName() + " cancelled their teleport request.");
    }

    private void remove(Request request, boolean notifyReplaced) {
        incoming.remove(request.target(), request);
        outgoing.remove(request.requester(), request);
        request.expiry().cancel();
        if (notifyReplaced) {
            Player oldRequester = Bukkit.getPlayer(request.requester());
            if (oldRequester != null) ClaimsPlugin.error(oldRequester, "Your request was replaced by a newer request.");
            Player oldTarget = Bukkit.getPlayer(request.target());
            if (oldTarget != null) ClaimsPlugin.error(oldTarget, "The previous teleport request was replaced.");
        }
    }

    private void begin(Player p, Location destination, String successMessage, java.util.function.BooleanSupplier stillValid, Runnable onSuccess) {
        if (!plugin.use(p)) return;
        if (cooldown.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis()) { ClaimsPlugin.error(p, "Please wait before teleporting again."); return; }
        if (p.isInsideVehicle() || p.isDead()) { ClaimsPlugin.error(p, "Leave your vehicle before teleporting."); return; }
        if (!plugin.allowed(p, destination)) { ClaimsPlugin.error(p, "You do not have access to that claim."); return; }
        cancel(p, false); p.closeInventory();
        UUID token = UUID.randomUUID();
        int delay = Math.max(0, Math.min(60, plugin.getConfig().getInt("teleport.warmup-seconds", 3)));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!valid(p, token)) return;
            destination.getWorld().getChunkAtAsync(destination).whenComplete((chunk, error) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                if (!valid(p, token)) return;
                if (error != null || !plugin.use(p) || !stillValid.getAsBoolean() || !plugin.allowed(p, destination) || !safe(destination) || p.isInsideVehicle() || p.isDead()) {
                    cancel(p, false); ClaimsPlugin.error(p, "Destination is unsafe, changed, or unavailable. Teleport cancelled."); return;
                }
                pending.remove(p.getUniqueId());
                // Chunk is loaded; perform on main thread so final permission/safety checks cannot race a later task.
                if (p.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                    cooldown.put(p.getUniqueId(), System.currentTimeMillis() + Math.max(0, plugin.getConfig().getInt("teleport.cooldown-seconds", 5)) * 1000L);
                    ClaimsPlugin.message(p, successMessage); onSuccess.run();
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
    void cancel(Player p, boolean tell) { var task = pending.remove(p.getUniqueId()); if (task != null) { task.task().cancel(); if (tell) ClaimsPlugin.error(p, "Teleport cancelled."); } }
    @EventHandler(ignoreCancelled = true) public void move(PlayerMoveEvent e) {
        var task = pending.get(e.getPlayer().getUniqueId());
        if (task != null && e.getTo() != null && (!task.start().getWorld().equals(e.getTo().getWorld()) || task.start().distanceSquared(e.getTo()) > 0.01)) cancel(e.getPlayer(), true);
    }
    @EventHandler(ignoreCancelled = true) public void damage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p) cancel(p, true); }
    @EventHandler public void quit(PlayerQuitEvent e) { Player player = e.getPlayer(); cancel(player, false); cooldown.remove(player.getUniqueId()); Request sent = outgoing.get(player.getUniqueId()); if (sent != null) { remove(sent, false); Player target = Bukkit.getPlayer(sent.target()); if (target != null) ClaimsPlugin.error(target, "Teleport request cancelled because the requester left."); } Request received = incoming.get(player.getUniqueId()); if (received != null) { remove(received, false); Player requester = Bukkit.getPlayer(received.requester()); if (requester != null) ClaimsPlugin.error(requester, "Teleport request cancelled because the target left."); } }
    void close() { pending.values().forEach(p -> p.task().cancel()); pending.clear(); incoming.values().forEach(r -> r.expiry().cancel()); incoming.clear(); outgoing.clear(); }
}
