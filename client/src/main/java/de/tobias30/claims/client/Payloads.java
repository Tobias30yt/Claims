package de.tobias30.claims.client;

import de.tobias30.claims.shared.Wire;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.function.Function;

final class Payloads {
    private Payloads() {}
    static <T extends CustomPacketPayload> StreamCodec<RegistryFriendlyByteBuf, T> raw(Function<byte[], T> create, Function<T, byte[]> bytes) {
        return new StreamCodec<>() {
            public T decode(RegistryFriendlyByteBuf buf) {
                if (buf.readableBytes() > Wire.MAX_BYTES) throw new IllegalArgumentException("Claims packet too large");
                byte[] data = new byte[buf.readableBytes()]; buf.readBytes(data); return create.apply(data);
            }
            public void encode(RegistryFriendlyByteBuf buf, T payload) {
                byte[] data = bytes.apply(payload);
                if (data.length > Wire.MAX_BYTES) throw new IllegalArgumentException("Claims packet too large");
                buf.writeBytes(data);
            }
        };
    }
    record Hello(byte[] bytes) implements CustomPacketPayload {
        static final Type<Hello> TYPE = new Type<>(Identifier.parse("claims:hello"));
        static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = raw(Hello::new, Hello::bytes);
        public Type<Hello> type() { return TYPE; }
    }
    record Menu(byte[] bytes) implements CustomPacketPayload {
        static final Type<Menu> TYPE = new Type<>(Identifier.parse("claims:menu"));
        static final StreamCodec<RegistryFriendlyByteBuf, Menu> CODEC = raw(Menu::new, Menu::bytes);
        public Type<Menu> type() { return TYPE; }
    }
    record Action(byte[] bytes) implements CustomPacketPayload {
        static final Type<Action> TYPE = new Type<>(Identifier.parse("claims:action"));
        static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = raw(Action::new, Action::bytes);
        public Type<Action> type() { return TYPE; }
    }
}
