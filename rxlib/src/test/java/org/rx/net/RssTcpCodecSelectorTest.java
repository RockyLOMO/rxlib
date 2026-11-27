package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.rx.net.socks.SocksConfig;

import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

class RssTcpCodecSelectorTest {
    @Test
    void shortRawGreetingPassesWithoutWaitingForFiveBytes() {
        EmbeddedChannel ch = new EmbeddedChannel(new RssTcpCodecSelector(new SocksConfig()));
        try {
            ByteBuf greeting = Unpooled.wrappedBuffer(new byte[]{5, 1, 0});
            assertTrue(ch.writeInbound(greeting));
            ByteBuf received = ch.readInbound();
            assertSame(greeting, received);
            received.release();
            assertFalse(Sockets.hasTcpCompressionHandlers(ch));
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void fragmentedZlibHeaderSelectsCompressionWithoutCertificate() {
        EmbeddedChannel ch = new EmbeddedChannel(new RssTcpCodecSelector(new SocksConfig()));
        Deflater deflater = new Deflater(3);
        try {
            deflater.setInput(new byte[]{5, 1, 0});
            deflater.finish();
            byte[] encoded = new byte[64];
            int length = deflater.deflate(encoded);
            assertFalse(ch.writeInbound(Unpooled.wrappedBuffer(encoded, 0, 1)));
            assertTrue(ch.writeInbound(Unpooled.wrappedBuffer(encoded, 1, length - 1)));
            ByteBuf received = ch.readInbound();
            try {
                assertEquals(3, received.readableBytes());
                assertEquals(0x050100, received.readUnsignedMedium());
            } finally {
                received.release();
            }
            assertTrue(Sockets.hasTcpCompressionHandlers(ch));
        } finally {
            deflater.end();
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsInvalidHeaderAndTlsWithoutCertificate() {
        for (byte[] prefix : new byte[][]{{0x78, 0}, {0x16, 3, 1}, {0}}) {
            EmbeddedChannel ch = new EmbeddedChannel(new RssTcpCodecSelector(new SocksConfig()));
            try {
                assertFalse(ch.writeInbound(Unpooled.wrappedBuffer(prefix)));
                assertFalse(ch.isOpen());
            } finally {
                ch.finishAndReleaseAll();
            }
        }
    }
}
