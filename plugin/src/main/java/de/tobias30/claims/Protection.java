package de.tobias30.claims;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.*;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.*;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.projectiles.ProjectileSource;
import java.util.Objects;

/** Protection applies to full chunks, at every height and in every loaded world. */
public final class Protection implements Listener {
    private final ClaimsPlugin plugin;
    Protection(ClaimsPlugin plugin) { this.plugin = plugin; }
    private boolean protectedAt(Location l) { return plugin.data.claim(ClaimsPlugin.key(l)) != null; }
    private boolean denied(Player p, Location l) { return !plugin.allowed(p, l); }
    private void guard(Player p, Location l, Cancellable event) {
        if (denied(p, l)) { event.setCancelled(true); p.sendActionBar(net.kyori.adventure.text.Component.text("This chunk is protected.")); }
    }
    private java.util.UUID owner(Location l) { var c = plugin.data.claim(ClaimsPlugin.key(l)); return c == null ? null : c.owner(); }
    boolean boundary(Location a, Location b) { return !Objects.equals(owner(a), owner(b)); }
    private Player actor(Entity e) {
        if (e instanceof Player p) return p;
        if (e instanceof Projectile projectile && projectile.getShooter() instanceof Player p) return p;
        if (e instanceof Tameable tame && tame.getOwner() instanceof Player p) return p;
        return null;
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void breakBlock(BlockBreakEvent e) { guard(e.getPlayer(), e.getBlock().getLocation(), e); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void place(BlockPlaceEvent e) {
        guard(e.getPlayer(), e.getBlock().getLocation(), e);
        if (e instanceof BlockMultiPlaceEvent multi) for (var state : multi.getReplacedBlockStates()) guard(e.getPlayer(), state.getLocation(), e);
        if (e.getBlock().getState() instanceof org.bukkit.block.Chest) {
            for (var face : java.util.List.of(org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST)) {
                Block neighbor = e.getBlock().getRelative(face);
                if (neighbor.getState() instanceof org.bukkit.block.Chest) guard(e.getPlayer(), neighbor.getLocation(), e);
            }
        }
    }
    @EventHandler(priority = EventPriority.HIGH) public void interact(PlayerInteractEvent e) {
        if (e.getClickedBlock() != null && denied(e.getPlayer(), e.getClickedBlock().getLocation())) {
            e.setUseInteractedBlock(Event.Result.DENY); e.setUseItemInHand(Event.Result.DENY);
        }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void bucketEmpty(PlayerBucketEmptyEvent e) { guard(e.getPlayer(), e.getBlock().getLocation(), e); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void bucketFill(PlayerBucketFillEvent e) { guard(e.getPlayer(), e.getBlock().getLocation(), e); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void entityInteract(PlayerInteractEntityEvent e) { guard(e.getPlayer(), e.getRightClicked().getLocation(), e); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void entityInteractAt(PlayerInteractAtEntityEvent e) { guard(e.getPlayer(), e.getRightClicked().getLocation(), e); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void armorStand(PlayerArmorStandManipulateEvent e) { guard(e.getPlayer(), e.getRightClicked().getLocation(), e); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void entityPlace(EntityPlaceEvent e) {
        if (e.getPlayer() != null) guard(e.getPlayer(), e.getEntity().getLocation(), e);
        else if (protectedAt(e.getEntity().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void hangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() != null) guard(e.getPlayer(), e.getEntity().getLocation(), e);
        else if (protectedAt(e.getEntity().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void hangingBreak(HangingBreakEvent e) {
        if (!protectedAt(e.getEntity().getLocation())) return;
        if (e.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION) { e.setCancelled(true); return; }
        Player p = e instanceof HangingBreakByEntityEvent by ? actor(by.getRemover()) : null;
        if (p == null || denied(p, e.getEntity().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void damage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player || !protectedAt(e.getEntity().getLocation())) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || e.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) { e.setCancelled(true); return; }
        if (e instanceof EntityDamageByEntityEvent by) {
            Player p = actor(by.getDamager());
            if (p == null || denied(p, e.getEntity().getLocation())) e.setCancelled(true);
        }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void vehicleDamage(VehicleDamageEvent e) {
        if (protectedAt(e.getVehicle().getLocation())) { Player p = actor(e.getAttacker()); if (p == null || denied(p, e.getVehicle().getLocation())) e.setCancelled(true); }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void vehicleDestroy(VehicleDestroyEvent e) {
        if (protectedAt(e.getVehicle().getLocation())) { Player p = actor(e.getAttacker()); if (p == null || denied(p, e.getVehicle().getLocation())) e.setCancelled(true); }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void explosion(EntityExplodeEvent e) { e.blockList().removeIf(b -> protectedAt(b.getLocation())); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void explosion(BlockExplodeEvent e) { e.blockList().removeIf(b -> protectedAt(b.getLocation())); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void mobChange(EntityChangeBlockEvent e) {
        if (protectedAt(e.getBlock().getLocation())) { Player p = actor(e.getEntity()); if (p == null || denied(p, e.getBlock().getLocation())) e.setCancelled(true); }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void entityPhysical(EntityInteractEvent e) { if (protectedAt(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void burn(BlockBurnEvent e) { if (protectedAt(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void ignite(BlockIgniteEvent e) {
        if (protectedAt(e.getBlock().getLocation()) && (e.getPlayer() == null || denied(e.getPlayer(), e.getBlock().getLocation()))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void spread(BlockSpreadEvent e) {
        if (boundary(e.getSource().getLocation(), e.getBlock().getLocation()) || (e.getNewState().getType() == org.bukkit.Material.FIRE && protectedAt(e.getBlock().getLocation()))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void flow(BlockFromToEvent e) { if (boundary(e.getBlock().getLocation(), e.getToBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void piston(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) if (boundary(e.getBlock().getLocation(), b.getLocation()) || boundary(e.getBlock().getLocation(), b.getRelative(e.getDirection()).getLocation())) e.setCancelled(true);
        if (boundary(e.getBlock().getLocation(), e.getBlock().getRelative(e.getDirection()).getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void piston(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (boundary(e.getBlock().getLocation(), b.getLocation()) || boundary(e.getBlock().getLocation(), b.getRelative(e.getDirection()).getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void dispense(BlockDispenseEvent e) {
        if (e.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional d && boundary(e.getBlock().getLocation(), e.getBlock().getRelative(d.getFacing()).getLocation())) e.setCancelled(true);
    }
    private Location inventoryLocation(Inventory i) {
        if (i.getHolder() instanceof Entity e) return e.getLocation(); return i.getLocation();
    }
    private java.util.List<Location> inventoryLocations(Inventory i) {
        if (i.getHolder() instanceof org.bukkit.block.DoubleChest chest) {
            var result = new java.util.ArrayList<Location>();
            if (chest.getLeftSide() instanceof org.bukkit.block.BlockState left) result.add(left.getLocation());
            if (chest.getRightSide() instanceof org.bukkit.block.BlockState right) result.add(right.getLocation());
            return result;
        }
        Location location = inventoryLocation(i); return location == null ? java.util.List.of() : java.util.List.of(location);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void openInventory(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p) for (Location l : inventoryLocations(e.getInventory())) guard(p, l, e);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void hopper(InventoryMoveItemEvent e) {
        for (Location a : inventoryLocations(e.getSource())) for (Location b : inventoryLocations(e.getDestination())) if (boundary(a, b)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void grow(StructureGrowEvent e) { e.getBlocks().removeIf(b -> boundary(e.getLocation(), b.getLocation())); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void fertilize(BlockFertilizeEvent e) {
        if (e.getPlayer() != null && denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
        e.getBlocks().removeIf(b -> boundary(e.getBlock().getLocation(), b.getLocation()));
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void sign(SignChangeEvent e) { guard(e.getPlayer(), e.getBlock().getLocation(), e); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void harvest(PlayerHarvestBlockEvent e) { guard(e.getPlayer(), e.getHarvestedBlock().getLocation(), e); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void shear(PlayerShearEntityEvent e) { guard(e.getPlayer(), e.getEntity().getLocation(), e); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void leash(PlayerLeashEntityEvent e) { guard(e.getPlayer(), e.getEntity().getLocation(), e); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void unleash(PlayerUnleashEntityEvent e) { guard(e.getPlayer(), e.getEntity().getLocation(), e); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void fish(PlayerFishEvent e) { if (e.getCaught() != null) guard(e.getPlayer(), e.getCaught().getLocation(), e); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void projectile(ProjectileHitEvent e) {
        if (e.getHitBlock() != null && protectedAt(e.getHitBlock().getLocation())) {
            Player p = actor(e.getEntity()); if (p == null || denied(p, e.getHitBlock().getLocation())) e.setCancelled(true);
        }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void entityBreak(io.papermc.paper.event.entity.EntityBreakEvent e) {
        if (!protectedAt(e.getEntity().getLocation())) return;
        if (e.getCause() == io.papermc.paper.event.entity.EntityBreakEvent.RemoveCause.EXPLOSION) { e.setCancelled(true); return; }
        Player p = e instanceof io.papermc.paper.event.entity.EntityBreakByEntityEvent by ? actor(by.getRemover()) : null;
        if (p == null || denied(p, e.getEntity().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void construct(io.papermc.paper.event.entity.EntityConstructEvent e) {
        if (e.getBlocks().stream().anyMatch(b -> protectedAt(b.getLocation()))) e.setCancelled(true);
    }
}
