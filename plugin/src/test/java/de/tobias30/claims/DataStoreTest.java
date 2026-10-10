package de.tobias30.claims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DataStoreTest {
    @TempDir Path directory;
    @Test void restartPreservesClaimsTrustHomesNamesAndWorldIsolation() throws Exception {
        Path file = directory.resolve("claims.dat"); var store = new DataStore(file);
        UUID owner = UUID.randomUUID(), trusted = UUID.randomUUID(), world = UUID.randomUUID();
        var key = new DataStore.Key(world, -2, 19); var other = new DataStore.Key(UUID.randomUUID(), -2, 19);
        var home = new DataStore.Home(owner, "base", world, -17.5, 64, 300, 90, -20);
        store.mutate(s -> { s.names.put(owner, "Tobias30"); s.claims.put(key, new DataStore.Claim(key, owner, Set.of(trusted))); s.homes.put(owner, new HashMap<>(Map.of("base", home))); });
        var restored = new DataStore(file);
        assertEquals(Set.of(trusted), restored.claim(key).trusted()); assertNull(restored.claim(other));
        assertEquals(home, restored.home(owner, "base")); assertEquals(owner, restored.player("TOBIAS30"));
        restored.mutate(s -> { s.claims.remove(key); s.homes.get(owner).remove("base"); });
        var deleted = new DataStore(file); assertNull(deleted.claim(key)); assertTrue(deleted.homes(owner).isEmpty());
    }
    @Test void failedWriteDoesNotPublishMutation() throws Exception {
        Path file = directory.resolve("claims.dat"); var store = new DataStore(file); UUID owner = UUID.randomUUID();
        store.mutate(s -> s.names.put(owner, "Before"));
        Files.createDirectory(directory.resolve("claims.dat.tmp"));
        assertThrows(java.io.IOException.class, () -> store.mutate(s -> s.names.put(owner, "After")));
        assertEquals("Before", store.name(owner)); assertEquals("Before", new DataStore(file).name(owner));
    }
    @Test void corruptFilesFailClosed() throws Exception {
        Path file = directory.resolve("claims.dat"); Files.write(file, new byte[]{0, 1, 2});
        assertThrows(java.io.IOException.class, () -> new DataStore(file));
    }
}
