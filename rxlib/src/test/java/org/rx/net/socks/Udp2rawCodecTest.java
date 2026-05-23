package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class Udp2rawCodecTest {
    @Test
    void requestEncodeDecodeRoundTrip() {
        ByteBuf payload = Unpooled.copiedBuffer("hello-udp2raw", StandardCharsets.UTF_8);
        Udp2rawFrame frame = Udp2rawFrame.data(1L, 2L, 3L, 4L);
        frame.setFlags(Udp2rawCodec.FLAG_NEW_CONN | Udp2rawCodec.FLAG_HAS_CLIENT | Udp2rawCodec.FLAG_HAS_DST);
        frame.setClientSource(new InetSocketAddress("127.0.0.1", 31001));
        frame.setDestination(org.rx.net.Sockets.newUnresolvedEndpoint("example.com", 53));

        ByteBuf encoded = Udp2rawCodec.encode(UnpooledByteBufAllocator.DEFAULT, frame, payload);
        try {
            Udp2rawFrame decoded = Udp2rawCodec.decode(encoded);
            assertEquals(Udp2rawCodec.VERSION, decoded.getVersion());
            assertEquals(frame.getFlags(), decoded.getFlags());
            assertEquals(frame.getSessionHi(), decoded.getSessionHi());
            assertEquals(frame.getSessionLo(), decoded.getSessionLo());
            assertEquals(frame.getConnId(), decoded.getConnId());
            assertEquals(frame.getPacketSeq(), decoded.getPacketSeq());
            assertEquals(frame.getClientSource(), decoded.getClientSource());
            assertEquals(frame.getDestination(), decoded.getDestination());
            assertEquals("hello-udp2raw", encoded.toString(StandardCharsets.UTF_8));
        } finally {
            encoded.release();
        }
    }

    @Test
    void responseEncodeDecodeRoundTrip() {
        ByteBuf payload = Unpooled.copiedBuffer("world", StandardCharsets.UTF_8);
        Udp2rawFrame frame = Udp2rawFrame.data(11L, 22L, 33L, 44L);
        frame.setFlags(Udp2rawCodec.FLAG_HAS_SRC);
        frame.setSourceAddress(new InetSocketAddress("127.0.0.1", 16400));

        ByteBuf encoded = Udp2rawCodec.encode(UnpooledByteBufAllocator.DEFAULT, frame, payload);
        try {
            Udp2rawFrame decoded = Udp2rawCodec.decode(encoded);
            assertEquals(Udp2rawFrameType.DATA, decoded.getType());
            assertEquals(frame.getSourceAddress(), decoded.getSourceAddress());
            assertEquals("world", encoded.toString(StandardCharsets.UTF_8));
        } finally {
            encoded.release();
        }
    }
}
