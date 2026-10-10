package de.tobias30.claims.client;

import de.tobias30.claims.shared.Wire;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.chat.Component;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class ClaimsClient implements ClientModInitializer {
    private boolean announced;
    @Override public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(Payloads.Hello.TYPE, Payloads.Hello.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Payloads.Action.TYPE, Payloads.Action.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.Menu.TYPE, Payloads.Menu.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Menu.TYPE, (payload, context) -> {
            try {
                Wire.Menu menu = Wire.menu(payload.bytes());
                context.client().execute(() -> context.client().setScreenAndShow(new ClaimsScreen(menu)));
            } catch (IOException e) {
                System.getLogger("Claims Client").log(System.Logger.Level.WARNING, "Invalid Claims menu; use /claims chest for fallback.", e);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { announced = false; });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> { announced = false; });
        // Paper advertises plugin channels after joining. Wait until registration arrives.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!announced && client.getConnection() != null && ClientPlayNetworking.canSend(Payloads.Hello.TYPE) && ClientPlayNetworking.canSend(Payloads.Action.TYPE)) {
                ClientPlayNetworking.send(new Payloads.Hello(ByteBuffer.allocate(4).putInt(Wire.VERSION).array())); announced = true;
            }
        });
    }
}
