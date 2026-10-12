package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.rx.net.socks.encryption.ICrypto;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CipherCodecTest {
    @Test
    void emptyTcpWrite_doesNotInitializeCipher() {
        EmbeddedChannel channel = new EmbeddedChannel(CipherCodec.DEFAULT, new SSProtocolCodec());
        channel.attr(ShadowsocksConfig.CIPHER).set(ICrypto.get("aes-256-gcm", "test-password"));
        try {
            assertTrue(channel.writeOutbound(Unpooled.EMPTY_BUFFER));
            ByteBuf flushed = channel.readOutbound();
            try {
                assertEquals(0, flushed.readableBytes(), "flush marker must not emit a salt or encrypted record");
            } finally {
                flushed.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void closeOnFlushed_waitsForPendingEncryptedWrites() {
        List<Object> pending = new ArrayList<>();
        List<ChannelPromise> promises = new ArrayList<>();
        ChannelOutboundHandlerAdapter delayedTransport = new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                pending.add(msg);
                promises.add(promise);
            }
        };
        EmbeddedChannel channel = new EmbeddedChannel(delayedTransport, CipherCodec.DEFAULT, new SSProtocolCodec());
        channel.attr(ShadowsocksConfig.CIPHER).set(ICrypto.get("aes-256-gcm", "test-password"));
        try {
            ChannelPromise payloadPromise = channel.newPromise();
            channel.writeAndFlush(Unpooled.buffer(8).writeLong(1L), payloadPromise);
            assertEquals(1, pending.size());
            assertFalse(payloadPromise.isDone());

            Sockets.closeOnFlushed(channel);
            channel.runPendingTasks();
            assertTrue(channel.isActive(), "close must wait while ciphertext is still pending");
            assertEquals(2, pending.size());
            assertEquals(0, ((ByteBuf) pending.get(1)).readableBytes());
            promises.get(0).setSuccess();
            assertTrue(channel.isActive());
            promises.get(1).setSuccess();
            channel.runPendingTasks();
            assertFalse(channel.isOpen());
        } finally {
            for (Object msg : pending) {
                ReferenceCountUtil.release(msg);
            }
            for (ChannelPromise promise : promises) {
                promise.trySuccess();
            }
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void decode_releasesDecryptedBufferWhenOwnershipTransferFails() {
        CipherCodec codec = new CipherCodec();
        EmbeddedChannel channel = new EmbeddedChannel(codec);
        ByteBuf in = Bytes.directBuffer(8).writeLong(1L);
        TrackingCrypto crypto = new TrackingCrypto();
        channel.attr(ShadowsocksConfig.CIPHER).set(crypto);

        ChannelHandlerContext ctx = channel.pipeline().context(codec);
        List<Object> out = new AbstractList<Object>() {
            @Override
            public Object get(int index) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int size() {
                return 0;
            }

            @Override
            public void add(int index, Object element) {
                throw new IllegalStateException("inject add failure");
            }
        };

        try {
            assertThrows(IllegalStateException.class, () -> codec.decode(ctx, in, out));
            assertEquals(0, crypto.out.refCnt());
        } finally {
            ReferenceCountUtil.release(in);
            channel.finishAndReleaseAll();
        }
    }

    private static class TrackingCrypto implements ICrypto {
        ByteBuf out;

        @Override
        public void setForUdp(boolean forUdp) {
        }

        @Override
        public ByteBuf encrypt(ByteBuf in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ByteBuf decrypt(ByteBuf in) {
            out = Bytes.directBuffer(16);
            out.writeLong(2L);
            return out;
        }
    }
}
