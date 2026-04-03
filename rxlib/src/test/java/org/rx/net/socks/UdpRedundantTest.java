package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

public class UdpRedundantTest {
    private static final InetSocketAddress LOCAL = Sockets.newAnyEndpoint(0);
    private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 12345);

    @Test
    public void testEncoder() {
        int multiplier = 3;
        UdpRedundantEncoder encoder = new UdpRedundantEncoder(multiplier, 0);
        EmbeddedChannel channel = new EmbeddedChannel(encoder);

        ByteBuf data = Unpooled.copiedBuffer("hello".getBytes());
        channel.writeOutbound(new DatagramPacket(data, REMOTE));

        for (int i = 0; i < multiplier; i++) {
            DatagramPacket out = channel.readOutbound();
            assertNotNull(out);
            ByteBuf content = out.content();
            assertEquals(UdpRedundantEncoder.HEADER_MAGIC, content.readInt());
            // seq id should be consistent for the same group
            int seqId = content.readInt();
            if (i == 0) {
                // we can't easily check actual sequence without tracking but it's incrementing
            }
            assertEquals("hello", content.readCharSequence(content.readableBytes(), java.nio.charset.StandardCharsets.UTF_8).toString());
        }
        assertNull(channel.readOutbound());
    }

    @Test
    public void testDecoder() {
        UdpRedundantDecoder decoder = new UdpRedundantDecoder();
        EmbeddedChannel channel = new EmbeddedChannel(decoder);

        int seqId = 100;
        // redundancy 3
        for (int i = 0; i < 3; i++) {
            ByteBuf buf = Unpooled.buffer();
            buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
            buf.writeInt(seqId);
            buf.writeBytes("hello".getBytes());
            channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
        }

        // Only one should pass
        DatagramPacket in = channel.readInbound();
        assertNotNull(in);
        assertEquals("hello", in.content().toString(java.nio.charset.StandardCharsets.UTF_8));
        assertNull(channel.readInbound());
    }

    @Test
    public void testDecoderSlidingWindow() {
        UdpRedundantDecoder decoder = new UdpRedundantDecoder();
        EmbeddedChannel channel = new EmbeddedChannel(decoder);

        // Send 100, 101, 102
        for (int seqId = 100; seqId <= 102; seqId++) {
            ByteBuf buf = Unpooled.buffer();
            buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
            buf.writeInt(seqId);
            buf.writeBytes("packet".getBytes());
            channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
            
            DatagramPacket out = channel.readInbound();
            assertNotNull(out);
            out.release();
        }

        // Send 100 again (should be discarded)
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
        buf.writeInt(100);
        buf.writeBytes("packet".getBytes());
        channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
        assertNull(channel.readInbound());

        // Send 164 (advances window to highest 164, mask 0=highest, bit 64 is out)
        // WINDOW_SIZE is 64. 164 - 102 = 62. 100 is now at index 64 which is out!
        buf = Unpooled.buffer();
        buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
        buf.writeInt(164);
        buf.writeBytes("packet".getBytes());
        channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
        assertNotNull(channel.readInbound());

        // Now 100 is out of window
        buf = Unpooled.buffer();
        buf.writeInt(UdpRedundantEncoder.HEADER_MAGIC);
        buf.writeInt(100);
        buf.writeBytes("packet".getBytes());
        channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
        assertNull(channel.readInbound()); // Discarded as too old
    }

    @Test
    public void testNonMagicPassthrough() {
        UdpRedundantDecoder decoder = new UdpRedundantDecoder();
        EmbeddedChannel channel = new EmbeddedChannel(decoder);

        ByteBuf data = Unpooled.copiedBuffer("plain data".getBytes());
        channel.writeInbound(new DatagramPacket(data, REMOTE, LOCAL));

        DatagramPacket out = channel.readInbound();
        assertNotNull(out);
        assertEquals("plain data", out.content().toString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
