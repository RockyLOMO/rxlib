package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class UdpCompressTest {
    private static final InetSocketAddress LOCAL = Sockets.newAnyEndpoint(0);
    private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 22345);

    @Test
    public void testEncoderCompressesPayloadForRegisteredPeer() {
        EmbeddedChannel channel = new EmbeddedChannel(new UdpCompressEncoder(buildConfig()));
        UdpRelayAttributes.initRedundantPeers(channel);
        UdpRelayAttributes.addRedundantPeer(channel, REMOTE);

        byte[] payload = repeatedPayload(256);
        ByteBuf data = Unpooled.wrappedBuffer(payload);
        channel.writeOutbound(new DatagramPacket(data, REMOTE));

        DatagramPacket out = channel.readOutbound();
        assertNotNull(out);
        ByteBuf content = out.content();
        assertEquals(UdpCompressSupport.HEADER_MAGIC, content.readInt());
        assertEquals(payload.length, content.readUnsignedShort());
        assertEquals(0, content.readUnsignedByte());
        assertEquals(0, content.readUnsignedByte());
        assertTrue(content.readableBytes() < payload.length, "压缩后负载应小于原始 payload");
        out.release();
        assertNull(channel.readOutbound());
    }

    @Test
    public void testEncoderPassthroughWhenPeerNotRegistered() {
        EmbeddedChannel channel = new EmbeddedChannel(new UdpCompressEncoder(buildConfig()));
        UdpRelayAttributes.initRedundantPeers(channel);

        ByteBuf data = Unpooled.copiedBuffer("plain-udp".getBytes(StandardCharsets.UTF_8));
        channel.writeOutbound(new DatagramPacket(data, REMOTE));

        DatagramPacket out = channel.readOutbound();
        assertNotNull(out);
        assertEquals("plain-udp", out.content().toString(StandardCharsets.UTF_8));
        out.release();
        assertNull(channel.readOutbound());
    }

    @Test
    public void testEncoderReleasesOriginalDatagramPacketWhenCompressed() {
        EmbeddedChannel channel = new EmbeddedChannel(new UdpCompressEncoder(buildConfig()));
        UdpRelayAttributes.initRedundantPeers(channel);
        UdpRelayAttributes.addRedundantPeer(channel, REMOTE);

        ByteBuf payload = Unpooled.directBuffer(256);
        payload.writeBytes(repeatedPayload(256));
        assertEquals(1, payload.refCnt());

        channel.writeOutbound(new DatagramPacket(payload, REMOTE));
        assertEquals(0, payload.refCnt(), "压缩成功后原始 payload 应被释放");

        DatagramPacket out = channel.readOutbound();
        assertNotNull(out);
        out.release();
        assertNull(channel.readOutbound());
    }

    @Test
    public void testDecoderPassthroughForNonCompressedPacket() {
        EmbeddedChannel channel = new EmbeddedChannel(new UdpCompressDecoder());

        ByteBuf data = Unpooled.copiedBuffer("plain data".getBytes(StandardCharsets.UTF_8));
        channel.writeInbound(new DatagramPacket(data, REMOTE, LOCAL));

        DatagramPacket out = channel.readInbound();
        assertNotNull(out);
        assertEquals("plain data", out.content().toString(StandardCharsets.UTF_8));
        out.release();
        assertNull(channel.readInbound());
    }

    @Test
    public void testDecoderDropsUnsupportedDictionaryPacket() {
        EmbeddedChannel channel = new EmbeddedChannel(new UdpCompressDecoder());

        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(UdpCompressSupport.HEADER_MAGIC);
        buf.writeShort(32);
        buf.writeByte(UdpCompressSupport.FLAG_DICT);
        buf.writeByte(1);
        buf.writeBytes(new byte[]{1, 2, 3, 4});

        channel.writeInbound(new DatagramPacket(buf, REMOTE, LOCAL));
        assertNull(channel.readInbound());
    }

    @Test
    public void testCompressionBeforeRedundantAndDecodeAfterDedup() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new UdpRedundantDecoder(),
                new UdpCompressDecoder(),
                new UdpRedundantEncoder(2, 0),
                new UdpCompressEncoder(buildConfig())
        );
        UdpRelayAttributes.initRedundantPeers(channel);
        UdpRelayAttributes.addRedundantPeer(channel, REMOTE);

        byte[] payload = repeatedPayload(320);
        channel.writeOutbound(new DatagramPacket(Unpooled.wrappedBuffer(payload), REMOTE));

        DatagramPacket first = channel.readOutbound();
        assertNotNull(first);
        assertEquals(UdpRedundantEncoder.HEADER_MAGIC, first.content().getInt(first.content().readerIndex()));
        assertEquals(UdpCompressSupport.HEADER_MAGIC,
                first.content().getInt(first.content().readerIndex() + UdpRedundantEncoder.HEADER_SIZE));

        DatagramPacket second = channel.readOutbound();
        assertNotNull(second);
        assertEquals(UdpRedundantEncoder.HEADER_MAGIC, second.content().getInt(second.content().readerIndex()));
        assertEquals(UdpCompressSupport.HEADER_MAGIC,
                second.content().getInt(second.content().readerIndex() + UdpRedundantEncoder.HEADER_SIZE));
        assertNull(channel.readOutbound());

        channel.writeInbound(new DatagramPacket(first.content().retain(), LOCAL, REMOTE));
        channel.writeInbound(new DatagramPacket(second.content().retain(), LOCAL, REMOTE));
        first.release();
        second.release();

        DatagramPacket decoded = channel.readInbound();
        assertNotNull(decoded);
        byte[] actual = new byte[decoded.content().readableBytes()];
        decoded.content().readBytes(actual);
        assertArrayEquals(payload, actual);
        decoded.release();
        assertNull(channel.readInbound(), "重复副本应先去重，再只解压一次");
    }

    private static UdpCompressConfig buildConfig() {
        UdpCompressConfig config = new UdpCompressConfig();
        config.setEnabled(true);
        config.setMinPayloadBytes(1);
        config.setMinSavingsBytes(1);
        config.setMinSavingsRatio(0.01D);
        return config;
    }

    private static byte[] repeatedPayload(int len) {
        byte[] payload = new byte[len];
        Arrays.fill(payload, (byte) 'A');
        return payload;
    }
}
