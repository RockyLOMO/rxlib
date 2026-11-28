package org.rx.net.transport.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.CorruptedFrameException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * Per-session AES-128-GCM authenticated encryption stream codec for Transport Core v2.
 * Installed strictly AFTER authenticated {@code Hello}/{@code HelloAck} and strictly INSIDE
 * {@link org.rx.net.rss.tcp.AdaptiveTcpHandler} and {@link org.rx.net.EgressCodec}.
 * Uses independent client-to-server and server-to-client 128-bit keys, 4-byte direction salts,
 * and 8-byte monotonic nonces (anti-replay + integrity + confidentiality).
 */
final class TransportAeadCodec extends ByteToMessageCodec<ByteBuf> {
    static final int KEY_MATERIAL_BYTES = 40; // c2sKey(16) + c2sSalt(4) + s2cKey(16) + s2cSalt(4)
    private static final int TAG_BYTES = 16;
    private static final int MAX_RECORD_BYTES = 16 * 1024;
    private static final byte DIR_C2S = 1;
    private static final byte DIR_S2C = 2;

    private final Direction tx;
    private final Direction rx;

    TransportAeadCodec(boolean client, byte[] keyMaterial) {
        super(ByteBuf.class);
        if (keyMaterial == null || keyMaterial.length < KEY_MATERIAL_BYTES) {
            throw new IllegalArgumentException("Insufficient AEAD key material");
        }
        try {
            if (client) {
                this.tx = new Direction(keyMaterial, 0, 16, DIR_C2S);
                this.rx = new Direction(keyMaterial, 20, 36, DIR_S2C);
            } else {
                this.tx = new Direction(keyMaterial, 20, 36, DIR_S2C);
                this.rx = new Direction(keyMaterial, 0, 16, DIR_C2S);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to initialize AES-128-GCM codec", e);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf && !((ByteBuf) msg).isReadable()) {
            ctx.write(msg, promise);
            return;
        }
        super.write(ctx, msg, promise);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) throws Exception {
        try {
            int size = in.readableBytes();
            if (size == 0) {
                return;
            }
            long records = (size + MAX_RECORD_BYTES - 1L) / MAX_RECORD_BYTES;
            out.ensureWritable(Math.toIntExact(size + records * (2 + TAG_BYTES)));
            while (in.isReadable()) {
                int len = Math.min(MAX_RECORD_BYTES, in.readableBytes());
                out.writeShort(len);
                tx.crypt(in, len, out, true);
            }
        } catch (Exception e) {
            ctx.close();
            throw e;
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        try {
            while (in.readableBytes() >= 2) {
                int len = in.getUnsignedShort(in.readerIndex());
                if (len == 0 || len > MAX_RECORD_BYTES) {
                    throw new CorruptedFrameException("Invalid Transport AEAD record length: " + len);
                }
                if (in.readableBytes() < 2 + len + TAG_BYTES) {
                    return;
                }
                in.skipBytes(2);
                ByteBuf plain = ctx.alloc().ioBuffer(len, len);
                try {
                    rx.crypt(in, len, plain, false);
                    out.add(plain);
                } catch (Throwable e) {
                    plain.release();
                    throw e;
                }
            }
        } catch (Exception e) {
            in.skipBytes(in.readableBytes());
            ctx.close();
            throw e;
        }
    }

    private static final class Direction {
        private final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        private final SecretKeySpec key;
        private final byte[] nonce = new byte[12];
        private final byte[] aad = new byte[3];
        private long seq;
        private long bytes;

        Direction(byte[] material, int keyOffset, int saltOffset, byte dirByte) throws GeneralSecurityException {
            this.key = new SecretKeySpec(material, keyOffset, 16, "AES");
            System.arraycopy(material, saltOffset, nonce, 0, 4);
            this.aad[0] = dirByte;
        }

        void crypt(ByteBuf src, int len, ByteBuf dst, boolean encrypt) throws GeneralSecurityException {
            if (++seq > (1L << 32) || (bytes += len) > (1L << 40)) {
                throw new GeneralSecurityException("Transport AEAD session key exhausted");
            }
            long n = seq;
            for (int i = 11; i >= 4; i--) {
                nonce[i] = (byte) n;
                n >>>= 8;
            }
            aad[1] = (byte) (len >>> 8);
            aad[2] = (byte) len;
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            int read = encrypt ? len : len + TAG_BYTES;
            int outLen = encrypt ? len + TAG_BYTES : len;
            dst.ensureWritable(outLen);
            int written = dst.writerIndex();
            ByteBuffer input;
            if (src.nioBufferCount() == 1) {
                input = src.nioBuffer(src.readerIndex(), read);
            } else {
                byte[] copy = new byte[read];
                src.getBytes(src.readerIndex(), copy);
                input = ByteBuffer.wrap(copy);
            }
            ByteBuffer output = dst.nioBuffer(written, outLen);
            int count = cipher.doFinal(input, output);
            dst.writerIndex(written + count);
            src.skipBytes(read);
        }
    }
}
