package za.co.neroland.neroeconomy.network;

//? if <1.20.5 {
/*import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class LegacyTradePayloadTest {
    @Test
    void tradeIntentRoundTripsOverTheLegacyBuffer() {
        DirectTradePayload original = new DirectTradePayload(12, new UUID(3, 4),
                DirectTradePayload.Action.CONFIRM, 15);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            DirectTradePayload.STREAM_CODEC.encode(buffer, original);
            assertEquals(original, DirectTradePayload.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void unknownActionCannotBecomeAKnownTradeIntent() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(12);
            buffer.writeUUID(new UUID(3, 4));
            buffer.writeVarInt(100);
            buffer.writeVarLong(15);
            assertNull(DirectTradePayload.STREAM_CODEC.decode(buffer).action());
        } finally {
            buffer.release();
        }
    }
}
*///?}
