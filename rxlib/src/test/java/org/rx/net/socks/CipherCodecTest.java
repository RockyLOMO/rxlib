package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.rx.codec.CodecUtil;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.rx.net.socks.encryption.CipherKind;
import org.rx.net.socks.encryption.ICrypto;
import org.rx.net.socks.encryption.ShadowSocksKey;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CipherCodecTest {
    @ParameterizedTest
    @EnumSource(CipherKind.class)
    void emptyTcpWrite_doesNotInitializeCipher(CipherKind kind) {
        EmbeddedChannel channel = new EmbeddedChannel(CipherCodec.DEFAULT, new SSProtocolCodec());
        channel.attr(ShadowsocksConfig.CIPHER).set(ICrypto.get(kind.getCipherName(), "test-password"));
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

    @ParameterizedTest
    @EnumSource(CipherKind.class)
    void closeOnFlushed_waitsForPendingEncryptedWrites(CipherKind kind) {
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
        channel.attr(ShadowsocksConfig.CIPHER).set(ICrypto.get(kind.getCipherName(), "test-password"));
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

    @ParameterizedTest
    @EnumSource(CipherKind.class)
    void tcpRejectsReservedLengthTamperingAndTruncationWithoutEmittingPlaintext(CipherKind kind) throws Exception {
        String method = kind.getCipherName();
        String password = "edge-matrix-password";
        int keyLen = kind == CipherKind.AES_128_GCM ? 16 : 32;

        // 1. Authenticated length with reserved upper bits set (0x4000 > 0x3FFF) must close and emit nothing.
        byte[] salt = new byte[keyLen];
        for (int i = 0; i < salt.length; i++) salt[i] = (byte) (i + 1);
        byte[] subkey = hkdfSubkey(new ShadowSocksKey(password, keyLen).getEncoded(), salt, keyLen);
        ByteBuf reservedWire = Unpooled.buffer(keyLen + 18).writeBytes(salt);
        byte[] zeroNonce = new byte[12];
        byte[] badLen = new byte[]{0x40, 0x00};
        if (kind == CipherKind.CHACHA20_IETF_POLY1305) {
            // Python cryptography HKDF-SHA1(ss-subkey) + ChaCha20Poly1305(nonce=0, plain=0x4000).
            reservedWire.writeBytes(CodecUtil.fromHex("0001c1b6c3a0b32a8e546de3c4fc92b31611"));
        } else {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(subkey, "AES"), new GCMParameterSpec(128, zeroNonce));
            reservedWire.writeBytes(cipher.doFinal(badLen));
        }
        EmbeddedChannel reservedCh = new EmbeddedChannel(CipherCodec.DEFAULT);
        reservedCh.attr(ShadowsocksConfig.CIPHER).set(ICrypto.get(method, password));
        try {
            assertFalse(reservedCh.writeInbound(reservedWire.retain()));
            assertNull(reservedCh.readInbound());
            assertFalse(reservedCh.isOpen(), "reserved length bit must close TCP channel");
        } finally {
            reservedWire.release();
            reservedCh.finishAndReleaseAll();
        }

        // 2. Tampered payload tag in a composite buffer must close and never leak unauthenticated plaintext.
        ICrypto enc = ICrypto.get(method, password);
        ByteBuf validWire = enc.encrypt(Unpooled.copiedBuffer("secret-payload-not-authenticated", StandardCharsets.UTF_8));
        CompositeByteBuf tampered = PooledByteBufAllocator.DEFAULT.compositeDirectBuffer(2);
        try {
            int split = keyLen + 20;
            ByteBuf head = PooledByteBufAllocator.DEFAULT.directBuffer(split).writeBytes(validWire, 0, split);
            ByteBuf tail = Unpooled.buffer(validWire.readableBytes() - split)
                    .writeBytes(validWire, split, validWire.readableBytes() - split);
            tail.setByte(tail.writerIndex() - 1, tail.getByte(tail.writerIndex() - 1) ^ 1);
            tampered.addComponent(true, head).addComponent(true, tail);

            EmbeddedChannel tamperCh = new EmbeddedChannel(CipherCodec.DEFAULT);
            tamperCh.attr(ShadowsocksConfig.CIPHER).set(ICrypto.get(method, password));
            try {
                assertFalse(tamperCh.writeInbound(tampered.retain()));
                assertNull(tamperCh.readInbound());
                assertFalse(tamperCh.isOpen());
            } finally {
                tamperCh.finishAndReleaseAll();
            }

            // 3. Mid-record EOF truncation must emit zero plaintext, close on half-close EOF, and release pending ciphertext.
            EmbeddedChannel truncatedCh = new EmbeddedChannel(CipherCodec.DEFAULT);
            truncatedCh.attr(ShadowsocksConfig.CIPHER).set(ICrypto.get(method, password));
            try {
                assertFalse(truncatedCh.writeInbound(validWire.retainedSlice(0, validWire.readableBytes() - 7)));
                assertNull(truncatedCh.readInbound());
                truncatedCh.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                assertFalse(truncatedCh.isOpen(), "mid-record EOF must reject and close TCP channel");
                assertNull(truncatedCh.readInbound());
            } finally {
                truncatedCh.finishAndReleaseAll();
            }
        } finally {
            tampered.release();
            validWire.release();
            if (enc instanceof AutoCloseable) ((AutoCloseable) enc).close();
        }
    }

    private static byte[] hkdfSubkey(byte[] master, byte[] salt, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(salt, "HmacSHA1"));
        byte[] prk = mac.doFinal(master);
        byte[] okm = new byte[length];
        byte[] prev = new byte[0];
        byte[] info = "ss-subkey".getBytes(StandardCharsets.US_ASCII);
        int pos = 0, counter = 1;
        while (pos < length) {
            mac.init(new SecretKeySpec(prk, "HmacSHA1"));
            mac.update(prev);
            mac.update(info);
            mac.update((byte) counter++);
            prev = mac.doFinal();
            int n = Math.min(prev.length, length - pos);
            System.arraycopy(prev, 0, okm, pos, n);
            pos += n;
        }
        return okm;
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
