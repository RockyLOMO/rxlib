package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SSProtocolCodecTest {

    @Test
    void assemblesOnlyTheFirstAddressAcrossPlaintextRecords() {
        for (String host : new String[]{"192.0.2.1", "2001:db8::1", "example.test"}) {
            ByteBuf address = Unpooled.buffer();
            try {
                UdpManager.encode(address, host, 443);
                for (int split = 1; split < address.readableBytes(); split++) {
                    EmbeddedChannel channel = new EmbeddedChannel(new SSProtocolCodec());
                    try {
                        assertFalse(channel.writeInbound(address.retainedSlice(0, split)));
                        ByteBuf last = Unpooled.buffer().writeBytes(address, split, address.readableBytes() - split).writeInt(42);
                        assertTrue(channel.writeInbound(last));
                        ByteBuf payload = channel.readInbound();
                        try {
                            assertEquals(4, payload.readableBytes());
                            assertEquals(42, payload.readInt());
                            assertEquals(UdpManager.decode(address.duplicate()), channel.attr(ShadowsocksConfig.REMOTE_DEST).get());
                        } finally {
                            payload.release();
                        }
                    } finally {
                        channel.finishAndReleaseAll();
                    }
                }
            } finally {
                address.release();
            }
        }
    }

    @Test
    void decode_preservesShortTcpPayloadsAfterAddress() {
        EmbeddedChannel channel = new EmbeddedChannel(new SSProtocolCodec());
        ByteBuf address = Unpooled.buffer();
        UdpManager.encode(address, "1.2.3.4", 443);
        try {
            assertTrue(channel.writeInbound(address));
            ((ByteBuf) channel.readInbound()).release();
            for (int size = 1; size <= 3; size++) {
                ByteBuf payload = Unpooled.buffer(size).writeZero(size);
                assertTrue(channel.writeInbound(payload), "TCP payload of " + size + " bytes must be forwarded");
                ByteBuf forwarded = channel.readInbound();
                try {
                    assertSame(payload, forwarded);
                    assertEquals(size, forwarded.readableBytes());
                } finally {
                    forwarded.release();
                }
                assertEquals(0, payload.refCnt());
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void decode_storesInetSocketAddressAttr() {
        EmbeddedChannel channel = new EmbeddedChannel(new SSProtocolCodec());
        ByteBuf packet = Unpooled.buffer();
        UdpManager.encode(packet, "1.2.3.4", 53);
        packet.writeByte(7);
        try {
            assertTrue(channel.writeInbound(packet));
            InetSocketAddress endpoint = channel.attr(ShadowsocksConfig.REMOTE_DEST).get();
            assertNotNull(endpoint);
            assertEquals("1.2.3.4", endpoint.getHostString());
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
            InetSocketAddress endpoint = UdpManager.decode(packet);
            assertEquals("1.2.3.4", endpoint.getHostString());
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
