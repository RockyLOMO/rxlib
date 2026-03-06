package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class FecCodecTest {
    static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 9999);
    static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 8888);

    // region FecPacket
    @Test
    public void testPacketEncodeDecode() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        FecPacket pkt = new FecPacket(42, 1, (byte) 0, false, payload);

        ByteBuf buf = Unpooled.buffer();
        pkt.encode(buf);

        FecPacket decoded = FecPacket.decode(buf);
        assertNotNull(decoded);
        assertEquals(42, decoded.getSeqNo());
        assertEquals(1, decoded.getGroupId());
        assertEquals(0, decoded.getGroupIdx());
        assertFalse(decoded.isParity());
        assertArrayEquals(payload, decoded.getPayload());
        buf.release();
    }

    @Test
    public void testPacketBadMagic() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(0x1234); // wrong magic
        buf.writeInt(1);
        buf.writeInt(1);
        buf.writeByte(0);
        buf.writeBoolean(false);
        buf.writeBytes("test".getBytes());

        FecPacket decoded = FecPacket.decode(buf);
        assertNull(decoded);
        buf.release();
    }
    // endregion

    // region XOR Parity
    @Test
    public void testXorParity() {
        byte[] a = {0x01, 0x02, 0x03};
        byte[] b = {0x10, 0x20, 0x30};
        byte[] c = {0x05, 0x06, 0x07};

        byte[][] buffers = {a, b, c};
        byte[] parity = FecEncoder.computeXorParity(buffers, 3, 3);

        // parity = a ^ b ^ c
        for (int i = 0; i < 3; i++) {
            assertEquals((byte) (a[i] ^ b[i] ^ c[i]), parity[i]);
        }

        // 恢复丢失的 b: b = parity ^ a ^ c
        byte[] recovered = new byte[3];
        for (int i = 0; i < 3; i++) {
            recovered[i] = (byte) (parity[i] ^ a[i] ^ c[i]);
        }
        assertArrayEquals(b, recovered);
    }

    @Test
    public void testXorParityDifferentLengths() {
        byte[] a = {0x01, 0x02};
        byte[] b = {0x10, 0x20, 0x30, 0x40};

        byte[][] buffers = {a, b};
        byte[] parity = FecEncoder.computeXorParity(buffers, 2, 4);

        // parity[0] = 0x01 ^ 0x10, parity[1] = 0x02 ^ 0x20, parity[2] = 0x30, parity[3] = 0x40
        assertEquals(4, parity.length);
        assertEquals((byte) (0x01 ^ 0x10), parity[0]);
        assertEquals((byte) (0x02 ^ 0x20), parity[1]);
        assertEquals((byte) 0x30, parity[2]);
        assertEquals((byte) 0x40, parity[3]);
    }
    // endregion

    // region Encoder
    @Test
    public void testEncoderEmitsParityOnFullGroup() {
        FecConfig config = new FecConfig();
        config.setGroupSize(3);
        config.setFlushTimeoutMs(0); // disable timer for test

        EmbeddedChannel channel = new EmbeddedChannel(new FecEncoder(config));

        // Write 3 data packets
        for (int i = 0; i < 3; i++) {
            ByteBuf data = Unpooled.wrappedBuffer(("pkt" + i).getBytes());
            channel.writeOutbound(new DatagramPacket(data, REMOTE));
        }
        channel.flush();

        // Should have 3 data + 1 parity = 4 outbound messages
        int count = 0;
        Object msg;
        FecPacket lastParity = null;
        while ((msg = channel.readOutbound()) != null) {
            DatagramPacket dp = (DatagramPacket) msg;
            ByteBuf content = dp.content();
            FecPacket pkt = FecPacket.decode(content);
            assertNotNull(pkt);
            if (pkt.isParity()) {
                lastParity = pkt;
            }
            count++;
            dp.release();
        }
        assertEquals(4, count); // 3 data + 1 parity
        assertNotNull(lastParity);
        assertTrue(lastParity.isParity());
    }
    // endregion

    // region Decoder recovery
    @Test
    public void testDecoderRecoversMissingPacket() {
        FecConfig config = new FecConfig();
        config.setGroupSize(3);
        config.setStaleGroupTimeoutMs(5000);

        EmbeddedChannel channel = new EmbeddedChannel(new FecDecoder(config));

        byte[] d0 = "hello".getBytes();
        byte[] d1 = "world".getBytes();
        byte[] d2 = "foooo".getBytes();
        int maxLen = Math.max(d0.length, Math.max(d1.length, d2.length));

        // Compute parity
        byte[][] buffers = {d0, d1, d2};
        byte[] parity = FecEncoder.computeXorParity(buffers, 3, maxLen);

        int groupId = 100;

        // Send d0 and d2 (skip d1 to simulate loss)
        FecPacket p0 = new FecPacket(1, groupId, (byte) 0, false, d0);
        FecPacket p2 = new FecPacket(3, groupId, (byte) 2, false, d2);
        FecPacket pp = new FecPacket(4, groupId, (byte) 3, true, parity);

        ByteBuf buf0 = Unpooled.buffer();
        p0.encode(buf0);
        channel.writeInbound(new DatagramPacket(buf0, LOCAL, REMOTE));

        ByteBuf buf2 = Unpooled.buffer();
        p2.encode(buf2);
        channel.writeInbound(new DatagramPacket(buf2, LOCAL, REMOTE));

        // 2 data packets should have been forwarded
        int receivedBefore = 0;
        while (channel.readInbound() != null) {
            receivedBefore++;
        }
        assertEquals(2, receivedBefore);

        // Now send parity -> should trigger recovery of d1
        ByteBuf bufP = Unpooled.buffer();
        pp.encode(bufP);
        channel.writeInbound(new DatagramPacket(bufP, LOCAL, REMOTE));

        DatagramPacket recoveredPkt = channel.readInbound();
        assertNotNull(recoveredPkt, "Should recover missing packet");
        byte[] recoveredBytes = new byte[recoveredPkt.content().readableBytes()];
        recoveredPkt.content().readBytes(recoveredBytes);
        assertArrayEquals(d1, recoveredBytes);
        recoveredPkt.release();
    }

    @Test
    public void testDecoderAllDataNoLoss() {
        FecConfig config = new FecConfig();
        config.setGroupSize(2);
        config.setStaleGroupTimeoutMs(5000);

        EmbeddedChannel channel = new EmbeddedChannel(new FecDecoder(config));

        byte[] d0 = "aa".getBytes();
        byte[] d1 = "bb".getBytes();
        int groupId = 200;

        // Send both data packets
        FecPacket p0 = new FecPacket(1, groupId, (byte) 0, false, d0);
        FecPacket p1 = new FecPacket(2, groupId, (byte) 1, false, d1);

        ByteBuf buf0 = Unpooled.buffer();
        p0.encode(buf0);
        channel.writeInbound(new DatagramPacket(buf0, LOCAL, REMOTE));

        ByteBuf buf1 = Unpooled.buffer();
        p1.encode(buf1);
        channel.writeInbound(new DatagramPacket(buf1, LOCAL, REMOTE));

        // Both data should be forwarded immediately
        int count = 0;
        DatagramPacket pkt;
        while ((pkt = channel.readInbound()) != null) {
            count++;
            pkt.release();
        }
        assertEquals(2, count);
    }

    @Test
    public void testDecoderParityLostNoImpact() {
        // If parity is lost but all data arrives, everything works fine
        FecConfig config = new FecConfig();
        config.setGroupSize(2);
        config.setStaleGroupTimeoutMs(5000);

        EmbeddedChannel channel = new EmbeddedChannel(new FecDecoder(config));

        byte[] d0 = "xx".getBytes();
        byte[] d1 = "yy".getBytes();
        int groupId = 300;

        FecPacket p0 = new FecPacket(1, groupId, (byte) 0, false, d0);
        FecPacket p1 = new FecPacket(2, groupId, (byte) 1, false, d1);

        ByteBuf buf0 = Unpooled.buffer();
        p0.encode(buf0);
        channel.writeInbound(new DatagramPacket(buf0, LOCAL, REMOTE));

        ByteBuf buf1 = Unpooled.buffer();
        p1.encode(buf1);
        channel.writeInbound(new DatagramPacket(buf1, LOCAL, REMOTE));

        // Skip parity – both data already forwarded
        int count = 0;
        DatagramPacket pkt;
        while ((pkt = channel.readInbound()) != null) {
            count++;
            pkt.release();
        }
        assertEquals(2, count);
    }

    @Test
    public void testMultipleGroups() {
        FecConfig config = new FecConfig();
        config.setGroupSize(2);
        config.setFlushTimeoutMs(0);

        EmbeddedChannel encoder = new EmbeddedChannel(new FecEncoder(config));

        // Write 4 data packets → 2 groups of 2
        for (int i = 0; i < 4; i++) {
            ByteBuf data = Unpooled.wrappedBuffer(("d" + i).getBytes());
            encoder.writeOutbound(new DatagramPacket(data, REMOTE));
        }
        encoder.flush();

        // Should emit 4 data + 2 parity = 6
        int count = 0;
        int parityCount = 0;
        Object msg;
        while ((msg = encoder.readOutbound()) != null) {
            DatagramPacket dp = (DatagramPacket) msg;
            FecPacket pkt = FecPacket.decode(dp.content());
            if (pkt != null && pkt.isParity()) {
                parityCount++;
            }
            count++;
            dp.release();
        }
        assertEquals(6, count);
        assertEquals(2, parityCount);
    }
    // endregion
}
