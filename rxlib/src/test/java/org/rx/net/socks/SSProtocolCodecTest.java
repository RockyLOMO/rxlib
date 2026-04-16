package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SSProtocolCodecTest {

    @Test
    void decode_storesUnresolvedEndpointAttr() {
        EmbeddedChannel channel = new EmbeddedChannel(new SSProtocolCodec());
        ByteBuf packet = Unpooled.buffer();
        UdpManager.encode(packet, "1.2.3.4", 53);
        packet.writeByte(7);
        try {
            assertTrue(channel.writeInbound(packet));
            UnresolvedEndpoint endpoint = channel.attr(ShadowsocksConfig.REMOTE_DEST).get();
            assertNotNull(endpoint);
            assertEquals("1.2.3.4", endpoint.getHost());
            assertEquals(53, endpoint.getPort());

            ByteBuf forwarded = channel.readInbound();
            assertNotNull(forwarded);
            forwarded.release();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void prependAddress_buildsCompositePacket() {
        ByteBuf payload = Unpooled.copiedBuffer("payload", StandardCharsets.UTF_8);
        CompositeByteBuf packet = UdpManager.prependAddress(PooledByteBufAllocator.DEFAULT, payload, new InetSocketAddress("1.2.3.4", 5300));
        try {
            UnresolvedEndpoint endpoint = UdpManager.decode(packet);
            assertEquals("1.2.3.4", endpoint.getHost());
            assertEquals(5300, endpoint.getPort());

            byte[] bytes = new byte[packet.readableBytes()];
            packet.readBytes(bytes);
            assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), bytes);
        } finally {
            packet.release();
            payload.release();
        }
    }
}
