package de.tobias30.claims;

import de.tobias30.claims.shared.Wire;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MenuSecurityTest {
    @TempDir Path directory;
    ClaimsPlugin plugin; Menus menus; MockedStatic<Bukkit> bukkit;
    @BeforeEach void setup() throws Exception {
        plugin = mock(ClaimsPlugin.class, CALLS_REAL_METHODS); plugin.data = new DataStore(directory.resolve("claims.dat"));
        var config = new YamlConfiguration(); config.set("client-menus", true); config.set("default-homes", 3); config.set("default-claims", 8);
        doReturn(config).when(plugin).getConfig();
        bukkit = mockStatic(Bukkit.class); bukkit.when(Bukkit::getMessenger).thenReturn(mock(Messenger.class)); menus = new Menus(plugin);
    }
    @AfterEach void cleanup() { bukkit.close(); }
    Player player() {
        Player p = mock(Player.class); when(p.getUniqueId()).thenReturn(UUID.randomUUID()); when(p.hasPermission("claims.use")).thenReturn(true);
        when(p.getEffectivePermissions()).thenReturn(Set.of()); menus.onPluginMessageReceived(Menus.HELLO, p, ByteBuffer.allocate(4).putInt(Wire.VERSION).array()); return p;
    }
    Wire.Menu latest(Player p) throws Exception {
        var bytes = ArgumentCaptor.forClass(byte[].class); verify(p, atLeastOnce()).sendPluginMessage(eq(plugin), eq(Menus.MENU), bytes.capture());
        return Wire.menu(bytes.getValue());
    }
    int action(Wire.Menu menu, String label) {
        for (int i = 0; i < menu.buttons().size(); i++) if (menu.buttons().get(i).label().equals(label)) return i;
        throw new AssertionError("Missing action " + label);
    }
    @Test void sessionsAreBoundToPlayerAndConsumedOnUse() throws Exception {
        Player first = player(), second = player(); menus.homes(first, first.getUniqueId(), 0); menus.homes(second, second.getUniqueId(), 0);
        Wire.Menu menu = latest(first); byte[] click = Wire.action(new Wire.Action(menu.session(), action(menu, "Claims"), ""));
        menus.onPluginMessageReceived(Menus.ACTION, second, click); verify(second, times(1)).sendPluginMessage(eq(plugin), eq(Menus.MENU), any());
        menus.onPluginMessageReceived(Menus.ACTION, first, click); verify(first, times(2)).sendPluginMessage(eq(plugin), eq(Menus.MENU), any());
        menus.onPluginMessageReceived(Menus.ACTION, first, click); verify(first, times(2)).sendPluginMessage(eq(plugin), eq(Menus.MENU), any());
    }
    @Test void adminPermissionIsRecheckedAfterMenuWasOpened() throws Exception {
        Player p = player(); when(p.hasPermission("claims.admin")).thenReturn(true); menus.admin(p, 0); Wire.Menu menu = latest(p);
        when(p.hasPermission("claims.admin")).thenReturn(false);
        menus.onPluginMessageReceived(Menus.ACTION, p, Wire.action(new Wire.Action(menu.session(), action(menu, "Reload configuration"), "")));
        verify(plugin, never()).reloadConfig();
    }
    @Test void invalidIndicesAndMalformedPacketsCannotExecuteActions() throws Exception {
        Player p = player(); menus.homes(p, p.getUniqueId(), 0); Wire.Menu menu = latest(p);
        for (int index : new int[]{-1, 999}) menus.onPluginMessageReceived(Menus.ACTION, p, Wire.action(new Wire.Action(menu.session(), index, "")));
        menus.onPluginMessageReceived(Menus.ACTION, p, new byte[]{0});
        verify(p, times(1)).sendPluginMessage(eq(plugin), eq(Menus.MENU), any());
    }
}
