package io.github.raincandyfx.rainium;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** One Rainium message (see {@link NetProtocol}) as the game's custom payload: the raw bytes, nothing added, so a Paper server reads it as a plugin message. */
record RainiumPayload(byte[] data) implements CustomPacketPayload {
    static final Type<RainiumPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rainium", "net"));
    static final StreamCodec<FriendlyByteBuf, RainiumPayload> CODEC = StreamCodec.ofMember(RainiumPayload::write, RainiumPayload::read);

    private static RainiumPayload read(FriendlyByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.readBytes(b);
        return new RainiumPayload(b);
    }

    private void write(FriendlyByteBuf buf) { buf.writeBytes(data); }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
