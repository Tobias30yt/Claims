package de.tobias30.claims;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProtectionTest {
    @TempDir Path directory;
    ClaimsPlugin plugin; Protection protection; World world; UUID owner, trusted;
    @BeforeEach void setup() throws Exception {
        plugin = mock(ClaimsPlugin.class, CALLS_REAL_METHODS); plugin.data = new DataStore(directory.resolve("claims.dat"));
        protection = new Protection(plugin); world = mock(World.class); when(world.getUID()).thenReturn(UUID.randomUUID());
        owner = UUID.randomUUID(); trusted = UUID.randomUUID(); var key = new DataStore.Key(world.getUID(), 0, 0);
        plugin.data.mutate(s -> s.claims.put(key, new DataStore.Claim(key, owner, Set.of(trusted))));
    }
    Player player(UUID id) { Player p = mock(Player.class); when(p.getUniqueId()).thenReturn(id); return p; }
    Block block(int x, int z) { Block b = mock(Block.class); when(b.getLocation()).thenReturn(new Location(world, x, 64, z)); return b; }
    @Test void strangersCannotBreakButOwnerTrustedAndExplicitBypassCan() {
        Block b = block(0, 0);
        Player stranger = player(UUID.randomUUID()); var denied = new BlockBreakEvent(b, stranger); protection.breakBlock(denied); assertTrue(denied.isCancelled());
        for (UUID id : List.of(owner, trusted)) { var event = new BlockBreakEvent(b, player(id)); protection.breakBlock(event); assertFalse(event.isCancelled()); }
        when(stranger.hasPermission("claims.bypass")).thenReturn(true); var bypass = new BlockBreakEvent(b, stranger); protection.breakBlock(bypass); assertFalse(bypass.isCancelled());
    }
    @Test void explosionsPruneOnlyClaimedBlocks() {
        var event = mock(EntityExplodeEvent.class); Block claimed = block(0, 0), wilderness = block(16, 0);
        var blocks = new ArrayList<>(List.of(claimed, wilderness)); when(event.blockList()).thenReturn(blocks);
        protection.explosion(event); assertEquals(List.of(wilderness), blocks);
    }
    @Test void fluidsCannotEnterClaimAcrossBoundary() {
        var event = mock(BlockFromToEvent.class); Block from = block(16, 0), to = block(15, 0); when(event.getBlock()).thenReturn(from); when(event.getToBlock()).thenReturn(to);
        protection.flow(event); verify(event).setCancelled(true);
    }
    @Test void pistonCannotPushWildernessBlocksIntoClaim() {
        var event = mock(BlockPistonExtendEvent.class); var piston = block(17, 0); var moved = block(16, 0);
        when(event.getBlock()).thenReturn(piston); when(event.getBlocks()).thenReturn(List.of(moved)); when(event.getDirection()).thenReturn(org.bukkit.block.BlockFace.WEST);
        Block destination = block(15, 0); when(moved.getRelative(org.bukkit.block.BlockFace.WEST)).thenReturn(destination); when(piston.getRelative(org.bukkit.block.BlockFace.WEST)).thenReturn(moved);
        protection.piston(event); verify(event).setCancelled(true);
    }
    @Test void negativeCoordinatesAndWorldsAreIndependent() {
        assertEquals(-1, ClaimsPlugin.key(new Location(world, -0.5, 80, 0)).x());
        assertEquals(-2, ClaimsPlugin.key(new Location(world, -17, 80, 0)).x());
        World other = mock(World.class); when(other.getUID()).thenReturn(UUID.randomUUID());
        assertTrue(plugin.allowed(player(UUID.randomUUID()), new Location(other, 0, 64, 0)));
    }
}
