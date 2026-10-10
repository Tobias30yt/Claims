package de.tobias30.claims;

import de.tobias30.claims.shared.Wire;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.sign.Side;
import io.papermc.paper.math.Position;
import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
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
    private static final int PAGE = 21;
    private static final int[] ENTRY_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
    private static final int[] FOOTER_SLOTS = {46,47,48,50,51,52};
    private record Action(Wire.Button button, Material icon, Consumer<String> callback) {}
    private record Session(UUID id, List<Action> actions, long expiry) {}
    private record Prompt(UUID session, int index, Location sign) {}
    private final ClaimsPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Set<UUID> nativeClients = new HashSet<>(), forcedChest = new HashSet<>();
    private static final class Holder implements InventoryHolder {
        final UUID id; final int[] actionSlots = new int[54]; Inventory inventory;
        Holder(UUID id) { this.id = id; Arrays.fill(actionSlots, -1); }
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
    void main(Player p) {
        var actions = new ArrayList<Action>();
        actions.add(button("Homes", "Saved destinations", Material.RED_BED, () -> homes(p, p.getUniqueId(), 0)));
        actions.add(button("Claims", "Your protected chunks", Material.GRASS_BLOCK, () -> claims(p, p.getUniqueId(), 0)));
        if (p.hasPermission("claims.admin")) actions.add(button("Admin menu", "Manage players, claims and homes", Material.COMMAND_BLOCK, () -> admin(p, 0)));
        show(p, "Claims", "Choose what you want to manage", actions, actions.size(), "");
    }
    private Action button(String label, String detail, Material icon, Runnable callback) {
        return new Action(new Wire.Button(label, detail, false, false), icon, ignored -> callback.run());
    }
    private Action input(String label, String detail, Consumer<String> callback) {
        return new Action(new Wire.Button(label, detail, true, false), Material.NAME_TAG, callback);
    }
    private Action danger(String label, String detail, Runnable callback) {
        return new Action(new Wire.Button(label, detail, false, true), Material.RED_DYE, ignored -> callback.run());
    }
    private void show(Player p, String title, String subtitle, List<Action> actions) { show(p, title, subtitle, actions, 0, ""); }
    private void show(Player p, String title, String subtitle, List<Action> actions, int entryCount, String emptyState) {
        if (!plugin.use(p)) return;
        long navCount = actions.stream().filter(a -> isNavigation(a.button().label())).count();
        long footerCount = actions.size() - entryCount - navCount;
        if (entryCount < 0 || entryCount > ENTRY_SLOTS.length || footerCount > FOOTER_SLOTS.length || entryCount > actions.size()) throw new IllegalArgumentException("Menu layout exceeds its visible slots");
        UUID id = UUID.randomUUID(); var session = new Session(id, List.copyOf(actions), System.currentTimeMillis() + 600_000);
        prompts.remove(p.getUniqueId()); sessions.put(p.getUniqueId(), session);
        if (plugin.getConfig().getBoolean("client-menus", true) && nativeClients.contains(p.getUniqueId()) && !forcedChest.contains(p.getUniqueId())) {
            p.closeInventory();
            p.sendPluginMessage(plugin, MENU, Wire.menu(new Wire.Menu(id, title, subtitle, actions.stream().map(Action::button).toList(), entryCount)));
            return;
        }
        Holder holder = new Holder(id);
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text(title, NamedTextColor.DARK_AQUA));
        for (int slot = 0; slot < 54; slot++) {
            int row = slot / 9, col = slot % 9;
            Material glass = row == 0 || row == 5 || col == 0 || col == 8 ? Material.CYAN_STAINED_GLASS_PANE : Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            holder.inventory.setItem(slot, item(glass, " ", "", false));
        }
        holder.inventory.setItem(4, item(Material.HEART_OF_THE_SEA, title, subtitle + "\nYour changes save automatically.", false));
        if (entryCount == 0 && !emptyState.isBlank()) holder.inventory.setItem(22, item(Material.BOOK, "Nothing here yet", emptyState, false));
        for (int i = 0; i < entryCount; i++) {
            int slot = title.equals("Claims") && entryCount <= 3 ? 20 + i * 2 : ENTRY_SLOTS[i];
            Action a = actions.get(i); holder.inventory.setItem(slot, item(a.icon(), a.button().label(), a.button().detail(), a.button().danger()));
            holder.actionSlots[slot] = i;
        }
        int footer = 0;
        for (int i = entryCount; i < actions.size(); i++) {
            Action a = actions.get(i); String label = a.button().label();
            if (isNavigation(label)) {
                boolean previous = label.equalsIgnoreCase("Previous page");
                holder.inventory.setItem(previous ? 45 : 53, item(previous ? Material.ARROW : Material.SPECTRAL_ARROW, label, a.button().detail(), false));
                holder.actionSlots[previous ? 45 : 53] = i;
            } else {
                int slot = FOOTER_SLOTS[footer++];
                holder.inventory.setItem(slot, item(a.icon(), label, a.button().detail() + (a.button().input() ? "\nClick to open sign input." : ""), a.button().danger()));
                holder.actionSlots[slot] = i;
            }
        }
        holder.inventory.setItem(49, item(Material.BARRIER, "Close menu", "Press Escape or click to close.", false));
        p.openInventory(holder.inventory);
    }
    private boolean isNavigation(String label) { return label.equalsIgnoreCase("Previous page") || label.equalsIgnoreCase("Next page"); }
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
            actions.add(button(home.name(), plugin.world(home.world()) + "\n" + (int) home.x() + ", " + (int) home.y() + ", " + (int) home.z() + " • Click for options", Material.RED_BED, () -> home(p, owner, home.name())));
        }
        if (p.getUniqueId().equals(owner)) actions.add(input("Create new home", "Enter a home name (1–24 characters).", text -> { plugin.setHome(p, text); homes(p, owner, 0); }));
        pages(actions, current, homes.size(), n -> homes(p, owner, n));
        if (owner.equals(p.getUniqueId())) actions.add(button("Main menu", "Return to Homes, Claims and Admin", Material.ARROW, () -> main(p)));
        String limit = p.getUniqueId().equals(owner) ? " / " + Limits.display(plugin.limit(p, "homes")) : "";
        String subtitle = homes.isEmpty() ? "No homes yet • Create your first home below" : homes.size() + limit + " saved homes • Page " + (current + 1);
        show(p, owner.equals(p.getUniqueId()) ? "Your Homes" : plugin.data.name(owner) + "'s Homes", subtitle, actions, homes.size() - current * PAGE, homes.isEmpty() ? "Use Save home below to add your first destination." : "");
    }
    private void home(Player p, UUID owner, String name) {
        if (!owner(p, owner)) return;
        var h = plugin.data.home(owner, name); if (h == null) { homes(p, owner, 0); return; }
        show(p, name, plugin.world(h.world()) + " • " + (int) h.x() + ", " + (int) h.y() + ", " + (int) h.z(), List.of(
            button("Teleport", "Stand still during the warmup.", Material.ENDER_PEARL, () -> plugin.teleports.home(p, owner, name)),
            danger("Delete home", "Delete this saved destination", () -> delete(p, owner, name)),
            button("All homes", "Return to your home list", Material.BOOK, () -> homes(p, owner, 0))));
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
            String label = claim.name().isBlank() ? "Chunk " + key.x() + ", " + key.z() : claim.name();
            actions.add(button(label, plugin.world(key.world()) + " • " + key.x() + ", " + key.z(), Material.GRASS_BLOCK, () -> claim(p, key)));
        }
        pages(actions, current, claims.size(), n -> claims(p, owner, n));
        if (owner.equals(p.getUniqueId())) actions.add(button("Main menu", "Return to Homes, Claims and Admin", Material.ARROW, () -> main(p)));
        String limit = owner.equals(p.getUniqueId()) ? " / " + Limits.display(plugin.limit(p, "claims")) : "";
        String subtitle = claims.isEmpty() ? "No claims yet • Claim the chunk you are in" : claims.size() + limit + " protected chunks • Page " + (current + 1);
        show(p, owner.equals(p.getUniqueId()) ? "Your Claims" : plugin.data.name(owner) + "'s Claims", subtitle, actions, claims.size() - current * PAGE, claims.isEmpty() ? "Use /claim while standing in a chunk to protect it." : "");
    }
    private void claim(Player p, DataStore.Key key) {
        var c = plugin.data.claim(key);
        if (c == null) { ClaimsPlugin.error(p, "Claim no longer exists."); return; }
        if (!owner(p, c.owner())) return;
        String title = c.name().isBlank() ? "Chunk " + key.x() + ", " + key.z() : c.name();
        show(p, title, plugin.world(key.world()) + " • Chunk " + key.x() + ", " + key.z() + " • " + plugin.data.name(c.owner()), List.of(
            input("Rename claim", "Enter a name (1–32 letters, digits, spaces, _ or -)", text -> { plugin.renameClaim(p, key, text); claim(p, key); }),
            input("Add whitelist player", "Enter a player name or UUID", text -> { plugin.trust(p, key, text, true); claim(p, key); }),
            button("Whitelist", c.trusted().size() + " allowed player(s) • Manage access", Material.PLAYER_HEAD, () -> trusted(p, key, 0)),
            danger("Unclaim", "Release this protected chunk", () -> release(p, c)),
            button("Back to claims", "Return to your claim list", Material.GRASS_BLOCK, () -> claims(p, c.owner(), 0))));
    }
    private void trusted(Player p, DataStore.Key key, int page) {
        var c = plugin.data.claim(key); if (c == null || !owner(p, c.owner())) return;
        var ids = c.trusted().stream().sorted(Comparator.comparing(plugin.data::name)).toList();
        int current = clamp(page, ids.size()); var actions = new ArrayList<Action>();
        for (UUID id : ids.subList(current * PAGE, Math.min(ids.size(), (current + 1) * PAGE))) actions.add(danger("Remove " + plugin.data.name(id), "Revoke access to this chunk", () -> confirm(p, "Remove " + plugin.data.name(id) + "?", () -> { plugin.trust(p, key, id.toString(), false); trusted(p, key, current); }, () -> trusted(p, key, current))));
        pages(actions, current, ids.size(), n -> trusted(p, key, n));
        actions.add(button("Back", "Chunk options", Material.ARROW, () -> claim(p, key)));
        show(p, "Trusted players", ids.isEmpty() ? "Nobody has access yet" : ids.size() + " people can build and use this chunk", actions, ids.size() - current * PAGE, ids.isEmpty() ? "Use Trust player in the chunk menu to add someone." : "");
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
        actions.add(button("Main menu", "Return to Homes, Claims and Admin", Material.ARROW, () -> main(p)));
        show(p, "Claims Admin", ids.isEmpty() ? "No player data yet • Players appear after joining" : ids.size() + " players • Page " + (current + 1), actions, ids.size() - current * PAGE, ids.isEmpty() ? "No player data yet. Players appear here after joining." : "");
    }
    private void player(Player p, UUID owner) {
        if (!plugin.admin(p)) return;
        show(p, plugin.data.name(owner), "Choose what you would like to manage.", List.of(
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
        if (e.getRawSlot() == 49) { sessions.remove(p.getUniqueId()); Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory()); return; }
        int raw = e.getRawSlot();
        Session session = sessions.get(p.getUniqueId());
        if (session == null || !session.id().equals(holder.id)) return;
        int index = raw >= 0 && raw < holder.actionSlots.length ? holder.actionSlots[raw] : -1;
        if (index < 0 || index >= session.actions().size()) return;
        int selected = index;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sessions.get(p.getUniqueId()) != session) return;
            if (session.actions().get(selected).button().input()) openSign(p, session.id(), selected);
            else execute(p, session.id(), selected, "");
        });
    }
    @EventHandler public void drag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true); }
    @EventHandler public void closed(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Holder holder && !prompts.containsKey(e.getPlayer().getUniqueId())) {
            var session = sessions.get(e.getPlayer().getUniqueId()); if (session != null && session.id().equals(holder.id)) sessions.remove(e.getPlayer().getUniqueId());
        }
    }
    private void openSign(Player p, UUID session, int index) {
        Location at = p.getLocation().getBlock().getRelative(0, 2, 0).getLocation();
        Prompt prompt = new Prompt(session, index, at); prompts.put(p.getUniqueId(), prompt);
        p.closeInventory();
        var signData = Material.OAK_SIGN.createBlockData();
        p.sendBlockChange(at, signData);
        var sign = (org.bukkit.block.Sign) signData.createBlockState();
        sign.setAllowedEditorUniqueId(p.getUniqueId());
        p.sendBlockUpdate(at, sign);
        p.openVirtualSign(Position.block(at), Side.FRONT);
        Bukkit.getScheduler().runTaskLater(plugin, () -> restorePrompt(p.getUniqueId(), prompt), 1200L);
    }
    private void restorePrompt(UUID player, Prompt prompt) {
        if (!prompts.remove(player, prompt)) return;
        Player p = Bukkit.getPlayer(player);
        if (p != null) p.sendBlockChange(prompt.sign(), prompt.sign().getBlock().getBlockData());
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void signChange(UncheckedSignChangeEvent e) {
        Prompt prompt = prompts.get(e.getPlayer().getUniqueId());
        var position = e.getEditedBlockPosition();
        if (prompt == null || position.blockX() != prompt.sign().getBlockX() || position.blockY() != prompt.sign().getBlockY() || position.blockZ() != prompt.sign().getBlockZ()) return;
        e.setCancelled(true);
        String text = e.lines().stream().map(line -> PlainTextComponentSerializer.plainText().serialize(line)).filter(line -> !line.isBlank()).findFirst().orElse("");
        restorePrompt(e.getPlayer().getUniqueId(), prompt);
        execute(e.getPlayer(), prompt.session(), prompt.index(), text);
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId(); Prompt prompt = prompts.get(id); if (prompt != null) restorePrompt(id, prompt); sessions.remove(id); nativeClients.remove(id); forcedChest.remove(id);
    }
    void close() {
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory();
        sessions.clear(); prompts.clear();
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin); Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin);
    }
}
