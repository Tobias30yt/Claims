package de.tobias30.claims.client;

import de.tobias30.claims.shared.Wire;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Real vanilla Screen, Button, EditBox and Tooltip widgets; no inventory reskin. */
final class ClaimsScreen extends Screen {
    private final Wire.Menu menu;
    private EditBox input;
    private int page;
    private String value = "";
    private boolean sent;
    ClaimsScreen(Wire.Menu menu) { super(Component.literal(menu.title())); this.menu = menu; }
    @Override protected void init() {
        int width = Math.min(420, this.width - 24), left = (this.width - width) / 2;
        boolean needsInput = menu.buttons().stream().anyMatch(Wire.Button::input);
        int bottom = this.height - (needsInput ? 80 : 52);
        int rows = Math.max(1, (bottom - 55) / 24), perPage = rows * 2;
        int totalPages = Math.max(1, (menu.buttons().size() + perPage - 1) / perPage);
        page = Math.min(page, totalPages - 1);
        int buttonWidth = (width - 8) / 2;
        for (int i = page * perPage; i < Math.min(menu.buttons().size(), (page + 1) * perPage); i++) {
            int index = i, cell = i - page * perPage;
            Wire.Button action = menu.buttons().get(i);
            Component label = Component.literal(action.label()).withStyle(action.danger() ? ChatFormatting.RED : ChatFormatting.WHITE);
            Button button = Button.builder(label, b -> send(index)).bounds(left + (cell % 2) * (buttonWidth + 8), 55 + (cell / 2) * 24, buttonWidth, 20).build();
            button.setTooltip(Tooltip.create(Component.literal(action.detail() + (action.input() ? "\nEnter a name below first." : ""))));
            addRenderableWidget(button);
        }
        if (needsInput) {
            input = new EditBox(font, left, this.height - 77, width, 20, Component.literal("Home name or player"));
            input.setMaxLength(64); input.setHint(Component.literal("Enter home name or player…")); input.setValue(value);
            input.setResponder(text -> value = text); addRenderableWidget(input);
        }
        if (totalPages > 1) {
            Button previous = Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(left, this.height - 48, 30, 20).build();
            previous.active = page > 0; addRenderableWidget(previous);
            Button next = Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(left + width - 30, this.height - 48, 30, 20).build();
            next.active = page + 1 < totalPages; addRenderableWidget(next);
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(this.width / 2 - 75, this.height - 27, 150, 20).build());
    }
    private void send(int index) {
        if (sent || !ClientPlayNetworking.canSend(Payloads.Action.TYPE)) return;
        if (menu.buttons().get(index).input() && value.isBlank()) {
            if (input != null) setFocused(input); return;
        }
        sent = true;
        ClientPlayNetworking.send(new Payloads.Action(Wire.action(new Wire.Action(menu.session(), index, menu.buttons().get(index).input() ? value.trim() : ""))));
        onClose();
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
        graphics.centeredText(font, font.plainSubstrByWidth(menu.subtitle(), Math.max(40, width - 24)), width / 2, 34, 0xFFA0A0A0);
    }
    @Override public boolean isPauseScreen() { return false; }
}
