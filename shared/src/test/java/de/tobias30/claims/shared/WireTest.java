package de.tobias30.claims.shared;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WireTest {
    @Test void menuRoundTripPreservesUnicodeAndActions() throws Exception {
        var menu = new Wire.Menu(UUID.randomUUID(), "Häuser", "8 / 10 • world", List.of(new Wire.Button("Löschen", "Confirm\n第二行", false, true), new Wire.Button("Save", "Name", true, false)));
        assertEquals(menu, Wire.menu(Wire.menu(menu)));
    }
    @Test void actionRoundTrip() throws Exception {
        var action = new Wire.Action(UUID.randomUUID(), 12, "home_1"); assertEquals(action, Wire.action(Wire.action(action)));
    }
    @Test void rejectsTruncatedMalformedAndOversizePackets() {
        var action = Wire.action(new Wire.Action(UUID.randomUUID(), 0, "home"));
        for (int length = 0; length < action.length; length++) {
            byte[] truncated = Arrays.copyOf(action, length); assertThrows(IOException.class, () -> Wire.action(truncated));
        }
        assertThrows(IOException.class, () -> Wire.menu(new byte[Wire.MAX_BYTES + 1]));
        assertThrows(IOException.class, () -> Wire.action(Arrays.copyOf(action, action.length + 1)));
        action[3] = 2; assertThrows(IOException.class, () -> Wire.action(action));
    }
    @Test void rejectsUnboundedInputAndButtons() {
        assertThrows(IOException.class, () -> Wire.action(Wire.action(new Wire.Action(UUID.randomUUID(), 0, "x".repeat(65)))));
        assertThrows(java.io.UncheckedIOException.class, () -> Wire.menu(new Wire.Menu(UUID.randomUUID(), "", "", Collections.nCopies(41, new Wire.Button("", "", false, false)))));
    }
}
