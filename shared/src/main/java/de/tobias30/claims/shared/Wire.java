package de.tobias30.claims.shared;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixed-width big-endian framing, deliberately independent of Minecraft codecs. */
public final class Wire {
    public static final int VERSION = 1, MAX_BYTES = 30000, MAX_BUTTONS = 40;
    public record Button(String label, String detail, boolean input, boolean danger) {}
    public record Menu(UUID session, String title, String subtitle, List<Button> buttons) {
        public Menu { buttons = List.copyOf(buttons); }
    }
    public record Action(UUID session, int index, String input) {}
    private Wire() {}
    private interface Writer { void write(DataOutputStream out) throws IOException; }
    private static byte[] encode(Writer writer) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(VERSION); writer.write(out); out.flush();
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("Payload too large");
            return bytes.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static DataInputStream input(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Payload too large");
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != VERSION) throw new IOException("Unsupported protocol");
        return in;
    }
    private static void text(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024) throw new IOException("Text too long");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 1024 || length > in.available()) throw new IOException("Invalid text length");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void end(DataInputStream in) throws IOException { if (in.available() != 0) throw new IOException("Trailing data"); }
    public static byte[] menu(Menu menu) {
        return encode(out -> {
            uuid(out, menu.session()); text(out, menu.title()); text(out, menu.subtitle());
            if (menu.buttons().size() > MAX_BUTTONS) throw new IOException("Too many buttons");
            out.writeInt(menu.buttons().size());
            for (Button b : menu.buttons()) { text(out, b.label()); text(out, b.detail()); out.writeBoolean(b.input()); out.writeBoolean(b.danger()); }
        });
    }
    public static Menu menu(byte[] bytes) throws IOException {
        var in = input(bytes); UUID id = uuid(in); String title = text(in), subtitle = text(in);
        int count = in.readInt(); if (count < 0 || count > MAX_BUTTONS) throw new IOException("Invalid button count");
        var buttons = new ArrayList<Button>();
        for (int i = 0; i < count; i++) buttons.add(new Button(text(in), text(in), in.readBoolean(), in.readBoolean()));
        end(in); return new Menu(id, title, subtitle, buttons);
    }
    public static byte[] action(Action action) {
        return encode(out -> { uuid(out, action.session()); out.writeInt(action.index()); text(out, action.input()); });
    }
    public static Action action(byte[] bytes) throws IOException {
        var in = input(bytes); var action = new Action(uuid(in), in.readInt(), text(in)); end(in);
        if (action.input().length() > 64) throw new IOException("Input too long");
        return action;
    }
}
