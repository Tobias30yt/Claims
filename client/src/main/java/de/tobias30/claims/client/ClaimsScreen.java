package de.tobias30.claims.client;

import de.tobias30.claims.shared.Wire;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native vanilla screen with a readable list, one clear footer and combined pagination. */
final class ClaimsScreen extends Screen {
    private final Wire.Menu menu;
    private EditBox input;
    private int page;
    private String value = "";
    private boolean sent;
    private final List<Card> cards = new ArrayList<>();
    private record Card(String detail, int left, int top, int width) {}

    ClaimsScreen(Wire.Menu menu) { super(Component.literal(menu.title())); this.menu = menu; }

    @Override protected void init() {
        cards.clear();
        int panelWidth = Math.min(500, this.width - 28), left = (this.width - panelWidth) / 2;
        int entryCount = menu.entryCount();
        List<Integer> footer = new ArrayList<>();
        boolean needsInput = false;
        int serverPrevious = -1, serverNext = -1;
        for (int i = entryCount; i < menu.buttons().size(); i++) {
            Wire.Button item = menu.buttons().get(i);
            if (item.label().equalsIgnoreCase("Previous page")) serverPrevious = i;
            else if (item.label().equalsIgnoreCase("Next page")) serverNext = i;
            else { footer.add(i); needsInput |= item.input(); }
        }

        int listBottom = this.height - (needsInput ? 100 : 70);
        int rows = entryCount == 0 ? 0 : Math.max(1, Math.min(6, (listBottom - 62 + 4) / 39));
        int perPage = Math.max(1, rows * 2);
        int totalPages = Math.max(1, (entryCount + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, totalPages - 1));
        int cardWidth = (panelWidth - 38) / 2;
        int visibleEnd = Math.min(entryCount, (page + 1) * perPage);
        for (int i = page * perPage; i < visibleEnd; i++) {
            int cell = i - page * perPage;
            int x = left + 12 + (cell % 2) * (cardWidth + 10);
            int y = 62 + (cell / 2) * 39;
            Wire.Button entry = menu.buttons().get(i);
            Component label = Component.literal(shorten(entry.label(), cardWidth - 16)).withStyle(entry.danger() ? ChatFormatting.RED : ChatFormatting.WHITE);
            int actionIndex = i;
            Button card = Button.builder(label, b -> send(actionIndex)).bounds(x, y, cardWidth, 34).build();
            card.setTooltip(Tooltip.create(Component.literal(entry.label() + "\n" + entry.detail())));
            addRenderableWidget(card);
            cards.add(new Card(entry.detail(), x + 8, y + 23, cardWidth - 16));
        }

        int localPrevious = page > 0 ? 1 : serverPrevious >= 0 ? 2 : 0;
        int localNext = page + 1 < totalPages ? 1 : serverNext >= 0 ? 2 : 0;
        if (entryCount > 0 || serverPrevious >= 0 || serverNext >= 0) {
            int previousAction = serverPrevious;
            int nextAction = serverNext;
            Button previous = Button.builder(Component.literal("‹"), b -> {
                if (page > 0) { page--; rebuildWidgets(); } else if (previousAction >= 0) send(previousAction);
            }).bounds(left + 4, 25, 34, 20).build();
            previous.active = localPrevious != 0; previous.setTooltip(Tooltip.create(Component.literal(localPrevious == 2 ? "Previous list" : "Previous entries")));
            addRenderableWidget(previous);
            Button next = Button.builder(Component.literal("›"), b -> {
                if (page + 1 < totalPages) { page++; rebuildWidgets(); } else if (nextAction >= 0) send(nextAction);
            }).bounds(left + panelWidth - 38, 25, 34, 20).build();
            next.active = localNext != 0; next.setTooltip(Tooltip.create(Component.literal(localNext == 2 ? "Next list" : "Next entries")));
            addRenderableWidget(next);
        }

        if (needsInput) {
            input = new EditBox(font, left + 10, this.height - 78, panelWidth - 20, 20, Component.literal("Enter a name"));
            input.setMaxLength(64); input.setHint(Component.literal("Enter a player or home name…")); input.setValue(value);
            input.setResponder(text -> value = text); addRenderableWidget(input);
        }
        addFooter(footer, left, panelWidth);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(this.width / 2 - 68, this.height - 27, 136, 20).build());
    }

    private void addFooter(List<Integer> footer, int left, int panelWidth) {
        int count = footer.size(); if (count == 0) return;
        int horizontalGap = 4, available = panelWidth - 20;
        int buttonWidth = Math.min(112, (available - (count - 1) * horizontalGap) / count);
        int total = count * buttonWidth + (count - 1) * horizontalGap;
        int start = left + (panelWidth - total) / 2;
        int y = this.height - 52;
        for (int n = 0; n < count; n++) {
            int index = footer.get(n); Wire.Button action = menu.buttons().get(index);
            Component label = Component.literal(shorten(action.label(), buttonWidth - 12)).withStyle(action.danger() ? ChatFormatting.RED : action.input() ? ChatFormatting.GREEN : ChatFormatting.WHITE);
            Button button = Button.builder(label, b -> send(index)).bounds(start + n * (buttonWidth + horizontalGap), y, buttonWidth, 20).build();
            button.setTooltip(Tooltip.create(Component.literal(action.detail() + (action.input() ? "\nEnter the answer in the field above." : ""))));
            addRenderableWidget(button);
        }
    }

    private String shorten(String text, int width) { return font.plainSubstrByWidth(text, Math.max(24, width)); }

    private void send(int index) {
        if (sent || !ClientPlayNetworking.canSend(Payloads.Action.TYPE)) return;
        if (menu.buttons().get(index).input() && value.isBlank()) {
            if (input != null) setFocused(input);
            return;
        }
        sent = true;
        ClientPlayNetworking.send(new Payloads.Action(Wire.action(new Wire.Action(menu.session(), index, menu.buttons().get(index).input() ? value.trim() : ""))));
        onClose();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
        graphics.centeredText(font, font.plainSubstrByWidth(menu.subtitle(), Math.max(40, width - 126)), width / 2, 35, 0xFFCCCCCC);
        if (menu.entryCount() > 0) {
            graphics.text(font, heading(menu.title()), (width - Math.min(500, width - 28)) / 2 + 12, 52, 0xFF8AC6FF);
            int bottom = height - (menu.buttons().stream().anyMatch(Wire.Button::input) ? 100 : 70);
            int rows = Math.max(1, Math.min(6, (bottom - 62 + 4) / 39));
            int pages = Math.max(1, (menu.entryCount() + rows * 2 - 1) / (rows * 2));
            graphics.centeredText(font, "View " + (page + 1) + " of " + pages, width / 2, 52, 0xFFB8C0CC);
            for (Card card : cards) {
                String trimmed = font.plainSubstrByWidth(card.detail().replace('\n', ' '), card.width());
                graphics.text(font, trimmed, card.left(), card.top(), 0xFFB8C0CC);
            }
        } else if (footerCount() == 0) {
            graphics.centeredText(font, "There is nothing to manage here yet.", width / 2, height / 2, 0xFFB8C0CC);
        }
    }

    private String heading(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        if (lower.contains("home")) return "SAVED HOMES";
        if (lower.contains("trusted")) return "TRUSTED PLAYERS";
        if (lower.contains("admin") || lower.contains("player")) return "PLAYERS";
        return "PROTECTED CHUNKS";
    }

    private int footerCount() {
        int count = 0;
        for (int i = menu.entryCount(); i < menu.buttons().size(); i++) if (!menu.buttons().get(i).label().equalsIgnoreCase("Previous page") && !menu.buttons().get(i).label().equalsIgnoreCase("Next page")) count++;
        return count;
    }

    @Override public boolean isPauseScreen() { return false; }
}
