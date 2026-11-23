package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import org.junit.jupiter.api.Test;
import org.rx.net.socks.SocksConfig;

import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RssHopAesTest {
    static final SecretKeySpec KEY = new SecretKeySpec(new byte[32], "HmacSHA256");

    @Test
    void mixedListenerSniffsPskThenAuthenticatedZlib() {
        EmbeddedChannel client = new EmbeddedChannel(new RssHopAes(true, KEY),
                ZlibCodecFactory.newZlibDecoder(ZlibWrapper.ZLIB), ZlibCodecFactory.newZlibEncoder(ZlibWrapper.ZLIB, 3));
        EmbeddedChannel server = new EmbeddedChannel(RssHopTls.serverSelector(new SocksConfig(), KEY));
        try {
            byte[] body = new byte[8192];
            Arrays.fill(body, (byte) 5);
            client.writeOutbound(Unpooled.wrappedBuffer(body));
            transfer(client, server, 1);
            transfer(server, client, 1);
            ByteBuf wire = client.readOutbound();
            assertTrue(wire.readableBytes() < 150);
            server.writeInbound(wire);
            assertTrue(RssHopAes.authenticated(server.pipeline()));
            assertTrue(Sockets.hasTcpCompressionHandlers(server));
            assertArrayEquals(body, take(server, body.length));
            server.writeOutbound(Unpooled.wrappedBuffer(body));
            transfer(server, client, 3);
            assertArrayEquals(body, take(client, body.length));
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    @Test
    void fragmentedHandshakeQueuedWriteAndBidirectionalRecords() {
        EmbeddedChannel client = new EmbeddedChannel(new RssHopAes(true, KEY));
        EmbeddedChannel server = new EmbeddedChannel(new RssHopAes(false, KEY));
        try {
            byte[] body = new byte[17000];
            for (int i = 0; i < body.length; i++) body[i] = (byte) i;
            ChannelPromise written = client.newPromise();
            client.writeAndFlush(Unpooled.wrappedBuffer(body), written);
            assertFalse(written.isDone());
            transfer(client, server, 2);
            assertFalse(RssHopAes.authenticated(server.pipeline()));
            transfer(server, client, 3);
            assertTrue(RssHopAes.authenticated(client.pipeline()));
            assertTrue(written.isSuccess());
            transfer(client, server, 913);
            assertTrue(RssHopAes.authenticated(server.pipeline()));
            assertArrayEquals(body, take(server, body.length));
            server.writeOutbound(Unpooled.wrappedBuffer(body));
            transfer(server, client, 701);
            assertArrayEquals(body, take(client, body.length));
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    @Test
    void replayedConnectionAndDuplicateRecordFailAuthentication() {
        EmbeddedChannel client = new EmbeddedChannel(new RssHopAes(true, KEY));
        EmbeddedChannel server = new EmbeddedChannel(new RssHopAes(false, KEY));
        EmbeddedChannel replay = new EmbeddedChannel(new RssHopAes(false, KEY));
        try {
            ByteBuf hello = client.readOutbound();
            replay.writeInbound(hello.retainedDuplicate());
            server.writeInbound(hello);
            ByteBuf stale = replay.readOutbound();
            ByteBuf fresh = server.readOutbound();
            assertNotEquals(stale, fresh);
            stale.release();
            client.writeInbound(fresh);
            client.writeOutbound(Unpooled.wrappedBuffer(new byte[]{5, 1, 0}));
            ByteBuf record = client.readOutbound();
            assertThrows(DecoderException.class, () -> replay.writeInbound(record.retainedDuplicate()));
            assertFalse(replay.isOpen());
            assertNull(replay.readInbound());
            server.writeInbound(record.retainedDuplicate());
            assertArrayEquals(new byte[]{5, 1, 0}, take(server, 3));
            assertThrows(DecoderException.class, () -> server.writeInbound(record));
            assertFalse(server.isOpen());
            assertNull(server.readInbound());
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
            replay.finishAndReleaseAll();
        }
    }

    @Test
    void wrongKeyRejectsServerBeforeSendingQueuedPlaintext() {
        byte[] wrong = new byte[32];
        Arrays.fill(wrong, (byte) 1);
        EmbeddedChannel client = new EmbeddedChannel(new RssHopAes(true, KEY));
        EmbeddedChannel server = new EmbeddedChannel(new RssHopAes(false, new SecretKeySpec(wrong, "HmacSHA256")));
        try {
            ByteBuf secret = Unpooled.wrappedBuffer(new byte[]{5, 1, 0});
            ChannelPromise queued = client.newPromise();
            client.writeAndFlush(secret, queued);
            transfer(client, server, 32);
            assertThrows(DecoderException.class, () -> transfer(server, client, 64));
            assertFalse(client.isOpen());
            assertTrue(queued.isDone());
            assertFalse(queued.isSuccess());
            assertEquals(0, secret.refCnt());
            assertNull(client.readOutbound());
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    private static void transfer(EmbeddedChannel from, EmbeddedChannel to, int chunk) {
        ByteBuf wire;
        while ((wire = from.readOutbound()) != null) {
            try {
                while (wire.isReadable()) to.writeInbound(wire.readRetainedSlice(Math.min(chunk, wire.readableBytes())));
            } finally {
                wire.release();
            }
        }
    }

    private static byte[] take(EmbeddedChannel ch, int size) {
        byte[] result = new byte[size];
        int pos = 0;
        ByteBuf value;
        while ((value = ch.readInbound()) != null) {
            try {
                int n = value.readableBytes();
                value.readBytes(result, pos, n);
                pos += n;
            } finally {
                value.release();
            }
        }
        assertEquals(size, pos);
        return result;
    }
}
