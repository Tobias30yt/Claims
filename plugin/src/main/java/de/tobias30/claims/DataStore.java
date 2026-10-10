package de.tobias30.claims;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Main-thread store. A mutation is published in memory only after durable atomic replacement. */
public final class DataStore {
    public record Key(UUID world, int x, int z) {}
    public record Claim(Key key, UUID owner, Set<UUID> trusted) {
        public Claim { trusted = Set.copyOf(trusted); }
    }
    public record Home(UUID owner, String name, UUID world, double x, double y, double z, float yaw, float pitch) {}
    public static final class State {
        final Map<Key, Claim> claims = new HashMap<>();
        final Map<UUID, Map<String, Home>> homes = new HashMap<>();
        final Map<UUID, String> names = new HashMap<>();
        State() {}
        State(State source) {
            claims.putAll(source.claims); names.putAll(source.names);
            source.homes.forEach((id, map) -> homes.put(id, new HashMap<>(map)));
        }
    }
    private final Path file;
    private State state = new State();
    public DataStore(Path file) throws IOException { this.file = file; if (Files.exists(file)) load(); }
    public Claim claim(Key key) { return state.claims.get(key); }
    public List<Claim> claims(UUID owner) {
        return state.claims.values().stream().filter(c -> owner == null || c.owner().equals(owner))
            .sorted(Comparator.comparing((Claim c) -> c.key().world()).thenComparingInt(c -> c.key().x()).thenComparingInt(c -> c.key().z())).toList();
    }
    public List<Home> homes(UUID owner) {
        return state.homes.getOrDefault(owner, Map.of()).values().stream().sorted(Comparator.comparing(Home::name)).toList();
    }
    public Home home(UUID owner, String name) { return state.homes.getOrDefault(owner, Map.of()).get(name); }
    public String name(UUID id) { return state.names.getOrDefault(id, id.toString().substring(0, 8)); }
    public UUID player(String name) {
        try { return UUID.fromString(name); } catch (IllegalArgumentException ignored) {}
        return state.names.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(name)).map(Map.Entry::getKey).findFirst().orElse(null);
    }
    public List<UUID> players() {
        var ids = new HashSet<>(state.names.keySet()); ids.addAll(state.homes.keySet());
        state.claims.values().forEach(c -> ids.add(c.owner()));
        return ids.stream().sorted(Comparator.comparing(this::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }
    public void mutate(Consumer<State> change) throws IOException {
        var next = new State(state); change.accept(next); save(next); state = next;
    }
    private void save(State next) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (var stream = new FileOutputStream(temporary.toFile()); var out = new DataOutputStream(new BufferedOutputStream(stream))) {
            out.writeInt(0x434C4D31); out.writeInt(next.names.size());
            for (var e : next.names.entrySet()) { uuid(out, e.getKey()); out.writeUTF(e.getValue()); }
            out.writeInt(next.claims.size());
            for (Claim c : next.claims.values()) {
                uuid(out, c.key().world()); out.writeInt(c.key().x()); out.writeInt(c.key().z()); uuid(out, c.owner());
                out.writeInt(c.trusted().size()); for (UUID id : c.trusted()) uuid(out, id);
            }
            out.writeInt(next.homes.values().stream().mapToInt(Map::size).sum());
            for (var map : next.homes.values()) for (Home h : map.values()) {
                uuid(out, h.owner()); out.writeUTF(h.name()); uuid(out, h.world());
                out.writeDouble(h.x()); out.writeDouble(h.y()); out.writeDouble(h.z()); out.writeFloat(h.yaw()); out.writeFloat(h.pitch());
            }
            out.flush(); stream.getFD().sync();
        }
        // Refuse non-atomic filesystems instead of risking loss of the last valid state.
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private void load() throws IOException {
        var next = new State();
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != 0x434C4D31) throw new IOException("Unknown Claims data format");
            for (int n = count(in); n > 0; n--) next.names.put(uuid(in), in.readUTF());
            for (int n = count(in); n > 0; n--) {
                var key = new Key(uuid(in), in.readInt(), in.readInt()); UUID owner = uuid(in); var trust = new HashSet<UUID>();
                for (int t = count(in); t > 0; t--) trust.add(uuid(in));
                if (next.claims.put(key, new Claim(key, owner, trust)) != null) throw new IOException("Duplicate claim");
            }
            for (int n = count(in); n > 0; n--) {
                var h = new Home(uuid(in), in.readUTF(), uuid(in), in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat());
                if (!Double.isFinite(h.x()) || !Double.isFinite(h.y()) || !Double.isFinite(h.z())) throw new IOException("Invalid home location");
                next.homes.computeIfAbsent(h.owner(), k -> new HashMap<>()).put(h.name(), h);
            }
            if (in.read() != -1) throw new IOException("Trailing data");
        }
        state = next;
    }
    private static int count(DataInputStream in) throws IOException {
        int n = in.readInt(); if (n < 0 || n > 1_000_000) throw new IOException("Invalid record count"); return n;
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
}
