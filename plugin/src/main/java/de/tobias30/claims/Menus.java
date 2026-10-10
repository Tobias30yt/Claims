package de.tobias30.claims;

import de.tobias30.claims.shared.Wire;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.messaging.PluginMessageListener;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class Menus implements Listener, PluginMessageListener {
    static final String HELLO = "claims:hello", MENU = "claims:menu", ACTION = "claims:action";
    private static final int PAGE = 14;
    private static final int[] SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
    private record Action(Wire.Button button, Material icon, Consumer<String> callback) {}
    private record Session(UUID id, List<Action> actions, long expiry) {}
    private record Prompt(UUID session, int index) {}
    private final ClaimsPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Set<UUID> nativeClients = new HashSet<>(), forcedChest = new HashSet<>();
    private static final class Holder implements InventoryHolder {
        final UUID id; Inventory inventory;
        Holder(UUID id) { this.id = id; }
        @Override public Inventory getInventory() { return inventory; }
    }
    Menus(ClaimsPlugin plugin) {
        this.plugin = plugin;
        var messenger = Bukkit.getMessenger();
        messenger.registerIncomingPluginChannel(plugin, HELLO, this);
        messenger.registerIncomingPluginChannel(plugin, ACTION, this);
        messenger.registerOutgoingPluginChannel(plugin, MENU);
    }
    void chest(Player p) { forcedChest.add(p.getUniqueId()); }
    void auto(Player p) { forcedChest.remove(p.getUniqueId()); }
    private Action button(String label, String detail, Material icon, Runnable callback) {
        return new Action(new Wire.Button(label, detail, false, false), icon, ignored -> callback.run());
    }
    private Action input(String label, String detail, Consumer<String> callback) {
        return new Action(new Wire.Button(label, detail, true, false), Material.NAME_TAG, callback);
    }
    private Action danger(String label, String detail, Runnable callback) {
        return new Action(new Wire.Button(label, detail, false, true), Material.RED_DYE, ignored -> callback.run());
    }
    private void show(Player p, String title, String subtitle, List<Action> actions) {
        if (!plugin.use(p)) return;
        if (actions.size() > SLOTS.length) throw new IllegalArgumentException("Menu too large");
        UUID id = UUID.randomUUID(); var session = new Session(id, List.copyOf(actions), System.currentTimeMillis() + 600_000);
        prompts.remove(p.getUniqueId()); sessions.put(p.getUniqueId(), session);
        if (plugin.getConfig().getBoolean("client-menus", true) && nativeClients.contains(p.getUniqueId()) && !forcedChest.contains(p.getUniqueId())) {
            p.closeInventory();
            p.sendPluginMessage(plugin, MENU, Wire.menu(new Wire.Menu(id, title, subtitle, actions.stream().map(Action::button).toList())));
            return;
        }
        Holder holder = new Holder(id);
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text(title, NamedTextColor.DARK_AQUA));
        for (int slot = 0; slot < 54; slot++) holder.inventory.setItem(slot, item(Material.GRAY_STAINED_GLASS_PANE, " ", "", false));
        for (int slot : SLOTS) holder.inventory.setItem(slot, null);
        holder.inventory.setItem(4, item(Material.HEART_OF_THE_SEA, title, subtitle, false));
        holder.inventory.setItem(49, item(Material.BARRIER, "Close", "Your changes are saved automatically.", false));
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            holder.inventory.setItem(SLOTS[i], item(a.icon(), a.button().label(), a.button().detail() + (a.button().input() ? "\nClick, then enter text in chat." : ""), a.button().danger()));
        }
        p.openInventory(holder.inventory);
    }
    private ItemStack item(Material material, String title, String detail, boolean danger) {
        ItemStack item = new ItemStack(material); var meta = item.getItemMeta();
        meta.displayName(Component.text(title, danger ? NamedTextColor.RED : NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(Arrays.stream(detail.split("\n")).map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta); return item;
    }
    private boolean owner(Player p, UUID owner) { if (plugin.use(p) && plugin.owns(p, owner)) return true; ClaimsPlugin.error(p, "Access denied."); return false; }
    void homes(Player p, UUID owner, int page) {
        if (!owner(p, owner)) return;
        var homes = plugin.data.homes(owner); var actions = new ArrayList<Action>();
        int current = clamp(page, homes.size());
        for (var home : homes.subList(current * PAGE, Math.min(homes.size(), (current + 1) * PAGE))) {
            actions.add(button(home.name(), plugin.world(home.world()) + " • " + (int) home.x() + ", " + (int) home.y() + ", " + (int) home.z() + "\nOpen home options", Material.RED_BED, () -> home(p, owner, home.name())));
        }
        if (p.getUniqueId().equals(owner)) actions.add(input("Save home", "Enter a home name (1–24 characters).", text -> plugin.setHome(p, text)));
        pages(actions, current, homes.size(), n -> homes(p, owner, n));
        actions.add(button("Claims", "Manage protected chunks", Material.GRASS_BLOCK, () -> claims(p, owner, 0)));
        if (p.hasPermission("claims.admin")) actions.add(button("Administration", "Browse all players", Material.COMMAND_BLOCK, () -> admin(p, 0)));
        String limit = p.getUniqueId().equals(owner) ? " / " + Limits.display(plugin.limit(p, "homes")) : "";
        show(p, "Homes · " + plugin.data.name(owner), homes.size() + limit + " homes • Page " + (current + 1), actions);
    }
    private void home(Player p, UUID owner, String name) {
        if (!owner(p, owner)) return;
        var h = plugin.data.home(owner, name); if (h == null) { homes(p, owner, 0); return; }
        show(p, "Home · " + name, plugin.world(h.world()) + " • " + (int) h.x() + ", " + (int) h.y() + ", " + (int) h.z(), List.of(
            button("Teleport", "Stand still during the warmup.", Material.ENDER_PEARL, () -> plugin.teleports.home(p, owner, name)),
            danger("Delete home", "Requires confirmation", () -> delete(p, owner, name)),
            button("Back", "All homes", Material.ARROW, () -> homes(p, owner, 0))));
    }
    void delete(Player p, UUID owner, String name) {
        if (!owner(p, owner)) return;
        if (plugin.data.home(owner, name) == null) { ClaimsPlugin.error(p, "Home not found."); return; }
        confirm(p, "Delete home " + name + "?", () -> { plugin.deleteHome(p, owner, name); homes(p, owner, 0); }, () -> home(p, owner, name));
    }
    void claims(Player p, UUID owner, int page) {
        if (!owner(p, owner)) return;
        var claims = plugin.data.claims(owner); var actions = new ArrayList<Action>(); int current = clamp(page, claims.size());
        for (var claim : claims.subList(current * PAGE, Math.min(claims.size(), (current + 1) * PAGE))) {
            var key = claim.key();
            actions.add(button("Chunk " + key.x() + ", " + key.z(), plugin.world(key.world()) + "\nExplosionproof • " + claim.trusted().size() + " trusted players", Material.GRASS_BLOCK, () -> claim(p, key)));
        }
        if (owner.equals(p.getUniqueId())) actions.add(button("Claim current chunk", "Protect your current chunk", Material.GOLDEN_SHOVEL, () -> { plugin.claim(p); claims(p, owner, current); }));
        pages(actions, current, claims.size(), n -> claims(p, owner, n));
        actions.add(button("Homes", "Manage saved locations", Material.RED_BED, () -> homes(p, owner, 0)));
        if (p.hasPermission("claims.admin")) actions.add(button("Administration", "Browse all players", Material.COMMAND_BLOCK, () -> admin(p, 0)));
        String limit = owner.equals(p.getUniqueId()) ? " / " + Limits.display(plugin.limit(p, "claims")) : "";
        show(p, "Claims · " + plugin.data.name(owner), claims.size() + limit + " chunks • Page " + (current + 1), actions);
    }
    private void claim(Player p, DataStore.Key key) {
        var c = plugin.data.claim(key);
        if (c == null) { ClaimsPlugin.error(p, "Claim no longer exists."); return; }
        if (!owner(p, c.owner())) return;
        show(p, "Chunk " + key.x() + ", " + key.z(), plugin.world(key.world()) + " • Owner: " + plugin.data.name(c.owner()), List.of(
            input("Trust player", "Grant building and container access in this chunk", text -> { plugin.trust(p, key, text, true); claim(p, key); }),
            button("Trusted players", c.trusted().size() + " players • Click to manage", Material.PLAYER_HEAD, () -> trusted(p, key, 0)),
            danger("Release claim", "Remove protection • Requires confirmation", () -> release(p, c)),
            button("Back", "All claims", Material.ARROW, () -> claims(p, c.owner(), 0))));
    }
    private void trusted(Player p, DataStore.Key key, int page) {
        var c = plugin.data.claim(key); if (c == null || !owner(p, c.owner())) return;
        var ids = c.trusted().stream().sorted(Comparator.comparing(plugin.data::name)).toList();
        int current = clamp(page, ids.size()); var actions = new ArrayList<Action>();
        for (UUID id : ids.subList(current * PAGE, Math.min(ids.size(), (current + 1) * PAGE))) actions.add(danger("Remove " + plugin.data.name(id), "Revoke access to this chunk", () -> confirm(p, "Remove " + plugin.data.name(id) + "?", () -> { plugin.trust(p, key, id.toString(), false); trusted(p, key, current); }, () -> trusted(p, key, current))));
        pages(actions, current, ids.size(), n -> trusted(p, key, n));
        actions.add(button("Back", "Chunk options", Material.ARROW, () -> claim(p, key)));
        show(p, "Trusted players", "Access includes building, entities and containers.", actions);
    }
    void release(Player p, DataStore.Claim c) {
        if (!owner(p, c.owner())) return;
        confirm(p, "Release chunk " + c.key().x() + ", " + c.key().z() + "?", () -> { plugin.unclaim(p, c.key(), c.owner()); claims(p, c.owner(), 0); }, () -> claim(p, c.key()));
    }
    void confirm(Player p, String title, Runnable yes, Runnable no) {
        show(p, title, "Please confirm this change.", List.of(danger("Confirm", "Apply this change", yes), button("Cancel", "Keep everything as it is", Material.LIME_DYE, no)));
    }
    void admin(Player p, int page) {
        if (!plugin.admin(p)) return;
        var ids = plugin.data.players(); int current = clamp(page, ids.size()); var actions = new ArrayList<Action>();
        for (UUID id : ids.subList(current * PAGE, Math.min(ids.size(), (current + 1) * PAGE))) actions.add(button(plugin.data.name(id), plugin.data.claims(id).size() + " claims • " + plugin.data.homes(id).size() + " homes", Material.PLAYER_HEAD, () -> player(p, id)));
        pages(actions, current, ids.size(), n -> admin(p, n));
        actions.add(input("Find player", "Name or UUID • Includes offline players", text -> {
            if (!plugin.admin(p)) return;
            UUID id = plugin.data.player(text); if (id == null) ClaimsPlugin.error(p, "Player not found."); else player(p, id);
        }));
        actions.add(button("Reload configuration", "Reload defaults, worlds and teleport settings", Material.REPEATER, () -> { if (plugin.admin(p)) { plugin.reloadConfig(); ClaimsPlugin.message(p, "Configuration reloaded."); admin(p, current); } }));
        actions.add(button("My homes", "Return to your homes", Material.RED_BED, () -> homes(p, p.getUniqueId(), 0)));
        show(p, "Claims Administration", ids.size() + " players • Page " + (current + 1), actions);
    }
    private void player(Player p, UUID owner) {
        if (!plugin.admin(p)) return;
        show(p, "Admin · " + plugin.data.name(owner), "Review claims, trust and homes for this player.", List.of(
            button("Claims", "View and release protected chunks", Material.GRASS_BLOCK, () -> claims(p, owner, 0)),
            button("Homes", "Inspect, teleport to or delete homes", Material.RED_BED, () -> homes(p, owner, 0)),
            button("Back", "All players", Material.ARROW, () -> admin(p, 0))));
    }
    private int clamp(int page, int count) { return Math.max(0, Math.min(page, Math.max(0, (count - 1) / PAGE))); }
    private void pages(List<Action> actions, int page, int count, Consumer<Integer> open) {
        if (page > 0) actions.add(button("Previous page", "Page " + page, Material.ARROW, () -> open.accept(page - 1)));
        if ((page + 1) * PAGE < count) actions.add(button("Next page", "Page " + (page + 2), Material.ARROW, () -> open.accept(page + 1)));
    }
    private void execute(Player p, UUID id, int index, String text) {
        Session session = sessions.get(p.getUniqueId());
        if (session == null || !session.id().equals(id) || session.expiry() < System.currentTimeMillis() || index < 0 || index >= session.actions().size() || !plugin.use(p)) return;
        if (text.length() > 64) return;
        // Consume before invoking, preventing duplicate/replayed destructive requests.
        sessions.remove(p.getUniqueId()); prompts.remove(p.getUniqueId());
        p.closeInventory(); session.actions().get(index).callback().accept(text.trim());
    }
    @Override public void onPluginMessageReceived(String channel, Player p, byte[] data) {
        if (channel.equals(HELLO)) {
            if (data.length == 4 && ByteBuffer.wrap(data).getInt() == Wire.VERSION) nativeClients.add(p.getUniqueId());
        } else if (channel.equals(ACTION) && nativeClients.contains(p.getUniqueId())) {
            try { var action = Wire.action(data); execute(p, action.session(), action.index(), action.input()); }
            catch (IOException ignored) { /* Untrusted clients cannot create actions or select other players' sessions. */ }
        }
    }
    @EventHandler public void click(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getRawSlot() == 49) { Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory()); return; }
        int index = -1; for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == e.getRawSlot()) index = i;
        Session session = sessions.get(p.getUniqueId());
        if (session == null || !session.id().equals(holder.id) || index < 0 || index >= session.actions().size()) return;
        int selected = index;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sessions.get(p.getUniqueId()) != session) return;
            if (session.actions().get(selected).button().input()) {
                prompts.put(p.getUniqueId(), new Prompt(session.id(), selected)); p.closeInventory();
                ClaimsPlugin.message(p, "Enter text in chat, or type 'cancel'. Expires in 60 seconds.");
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Prompt prompt = prompts.get(p.getUniqueId());
                    if (prompt != null && prompt.session().equals(session.id())) { prompts.remove(p.getUniqueId()); sessions.remove(p.getUniqueId(), session); }
                }, 1200L);
            } else execute(p, session.id(), selected, "");
        });
    }
    @EventHandler public void drag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true); }
    @EventHandler public void closed(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Holder holder && !prompts.containsKey(e.getPlayer().getUniqueId())) {
            var session = sessions.get(e.getPlayer().getUniqueId()); if (session != null && session.id().equals(holder.id)) sessions.remove(e.getPlayer().getUniqueId());
        }
    }
    @EventHandler(priority = EventPriority.LOWEST) public void chat(AsyncChatEvent e) {
        Prompt prompt = prompts.get(e.getPlayer().getUniqueId()); if (prompt == null) return;
        e.setCancelled(true); String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!prompts.remove(e.getPlayer().getUniqueId(), prompt)) return;
            if (text.equalsIgnoreCase("cancel")) { sessions.remove(e.getPlayer().getUniqueId()); ClaimsPlugin.message(e.getPlayer(), "Cancelled."); }
            else execute(e.getPlayer(), prompt.session(), prompt.index(), text);
        });
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId(); sessions.remove(id); prompts.remove(id); nativeClients.remove(id); forcedChest.remove(id);
    }
    void close() {
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory();
        sessions.clear(); prompts.clear();
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin); Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin);
    }
}
