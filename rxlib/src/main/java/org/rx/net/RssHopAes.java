package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.util.ReferenceCountUtil;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** PSK-authenticated record layer for the internal RSS TCP hop. One instance per connection. */
public final class RssHopAes extends ByteToMessageCodec<ByteBuf> {
    static final int MAGIC = 0xa5;
    private static final int SALT = 16, TAG = 16, MAX_RECORD = 16 * 1024;
    private static final byte[] LABEL = "RSS-TCP-AES-128-GCM-v1".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final boolean client;
    private final SecretKeySpec psk;
    private final byte[] clientSalt = new byte[SALT];
    private Direction tx, rx;
    private PendingWriteQueue pending;
    private ScheduledFuture<?> deadline;
    private boolean sentHello, authenticated;

    private static final class Key {
        static final SecretKeySpec VALUE = parseKey(System.getenv("RSS_TCP_AES_KEY"));
    }

    private static SecretKeySpec parseKey(String text) {
        if (text == null) return null;
        byte[] key = Base64.getDecoder().decode(text);
        try {
            if (key.length != 32) throw new IllegalArgumentException("RSS_TCP_AES_KEY requires 32 random bytes");
            return new SecretKeySpec(key, "HmacSHA256");
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public static boolean configured() {
        return Key.VALUE != null;
    }

    public static RssHopAes clientHandler() {
        return new RssHopAes(true, Key.VALUE);
    }

    static SecretKeySpec configuredKey() {
        return Key.VALUE;
    }

    public static boolean authenticated(ChannelPipeline pipeline) {
        RssHopAes aes = pipeline.get(RssHopAes.class);
        return aes != null && aes.authenticated;
    }

    RssHopAes(boolean client, SecretKeySpec psk) {
        super(ByteBuf.class);
        if (psk == null) throw new IllegalArgumentException("Missing RSS_TCP_AES_KEY");
        this.client = client;
        this.psk = psk;
    }

    @Override public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        if (ctx.channel().isActive()) begin(ctx);
    }

    @Override public void channelActive(ChannelHandlerContext ctx) throws Exception {
        begin(ctx);
        super.channelActive(ctx);
    }

    private void begin(ChannelHandlerContext ctx) {
        if (deadline == null) deadline = ctx.executor().schedule(() -> { ctx.close(); }, 10, TimeUnit.SECONDS);
        if (client && !sentHello) {
            sentHello = true;
            RANDOM.nextBytes(clientSalt);
            ctx.writeAndFlush(ctx.alloc().ioBuffer(1 + SALT).writeByte(MAGIC).writeBytes(clientSalt))
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            if (!ctx.channel().config().isAutoRead()) ctx.read();
        }
    }

    @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf) || (tx == null && !client)) {
            ReferenceCountUtil.release(msg);
            promise.tryFailure(new IllegalStateException("Invalid write to RSS AES channel"));
            ctx.close();
            return;
        }
        if (tx == null) {
            if (pending == null) pending = new PendingWriteQueue(ctx);
            if (pending.size() >= 32 || pending.bytes() + ((ByteBuf) msg).readableBytes() > 65536) {
                ReferenceCountUtil.release(msg);
                promise.tryFailure(new IllegalStateException("RSS AES handshake write limit exceeded"));
                ctx.close();
                return;
            }
            pending.add(msg, promise);
            return;
        }
        super.write(ctx, msg, promise);
    }

    @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        try {
            if (rx == null) {
                int hello = client ? SALT + 32 : 1 + SALT;
                if (in.readableBytes() < hello) return;
                handshake(ctx, in);
                return;
            }
            if (in.readableBytes() < 2) return;
            int len = in.getUnsignedShort(in.readerIndex());
            if (len == 0 || len > MAX_RECORD) throw new CorruptedFrameException("Invalid RSS AES record length");
            if (in.readableBytes() < 2 + len + TAG) return;
            in.skipBytes(2);
            ByteBuf plain = ctx.alloc().ioBuffer(len, len);
            try {
                rx.crypt(in, len, plain, false);
                if (!authenticated) markAuthenticated();
                out.add(plain);
            } catch (Throwable e) {
                plain.release();
                throw e;
            }
        } catch (Exception e) {
            in.skipBytes(in.readableBytes());
            ctx.close();
            throw e;
        }
    }

    private void handshake(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        byte[] serverSalt = new byte[SALT];
        if (client) {
            in.readBytes(serverSalt);
        } else {
            if (in.readUnsignedByte() != MAGIC) throw new CorruptedFrameException("Invalid RSS AES greeting");
            in.readBytes(clientSalt);
            RANDOM.nextBytes(serverSalt);
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(psk);
        byte[] proof = derive(mac, serverSalt, 0);
        if (client) {
            byte[] received = new byte[proof.length];
            in.readBytes(received);
            if (!MessageDigest.isEqual(proof, received)) throw new CorruptedFrameException("Invalid RSS AES peer proof");
        }
        byte[] keys = derive(mac, serverSalt, 1);
        try {
            tx = new Direction(keys, client ? 0 : 16);
            rx = new Direction(keys, client ? 16 : 0);
        } finally {
            Arrays.fill(keys, (byte) 0);
        }
        if (!client) {
            ctx.writeAndFlush(ctx.alloc().ioBuffer(SALT + proof.length).writeBytes(serverSalt).writeBytes(proof))
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            if (!ctx.channel().config().isAutoRead()) ctx.read();
            return;
        }
        markAuthenticated();
        if (pending != null) {
            while (!pending.isEmpty()) {
                ByteBuf next = ((ByteBuf) pending.current()).retain();
                super.write(ctx, next, pending.remove());
            }
            pending = null;
            ctx.flush();
        }
    }

    private byte[] derive(Mac mac, byte[] serverSalt, int purpose) {
        mac.update(LABEL);
        mac.update((byte) purpose);
        mac.update(clientSalt);
        return mac.doFinal(serverSalt);
    }

    private void markAuthenticated() {
        authenticated = true;
        if (deadline != null) deadline.cancel(false);
    }

    @Override protected void encode(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) throws Exception {
        try {
            int size = in.readableBytes();
            long records = (size + MAX_RECORD - 1L) / MAX_RECORD;
            out.ensureWritable(Math.toIntExact(size + records * (2 + TAG)));
            while (in.isReadable()) {
                int len = Math.min(MAX_RECORD, in.readableBytes());
                out.writeShort(len);
                tx.crypt(in, len, out, true);
            }
        } catch (Exception e) {
            ctx.close();
            throw e;
        }
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cleanup();
        super.channelInactive(ctx);
    }

    @Override public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        cleanup();
        super.handlerRemoved(ctx);
    }

    private void cleanup() {
        if (deadline != null) deadline.cancel(false);
        if (pending != null) {
            pending.removeAndFailAll(new ClosedChannelException());
            pending = null;
        }
    }

    private static final class Direction {
        private final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        private final SecretKeySpec key;
        private final byte[] nonce = new byte[12], aad = new byte[2];
        private long seq, bytes;

        Direction(byte[] keys, int offset) throws GeneralSecurityException {
            key = new SecretKeySpec(keys, offset, 16, "AES");
        }

        void crypt(ByteBuf src, int len, ByteBuf dst, boolean encrypt) throws GeneralSecurityException {
            if (++seq > (1L << 24) || (bytes += len) > (1L << 36)) throw new GeneralSecurityException("RSS AES key exhausted");
            long n = seq;
            for (int i = 11; i >= 4; i--) {
                nonce[i] = (byte) n;
                n >>>= 8;
            }
            aad[0] = (byte) (len >>> 8);
            aad[1] = (byte) len;
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            int read = encrypt ? len : len + TAG, written = dst.writerIndex();
            ByteBuffer input = src.nioBuffer(src.readerIndex(), read);
            int count = cipher.doFinal(input, dst.nioBuffer(written, encrypt ? len + TAG : len));
            dst.writerIndex(written + count);
            src.skipBytes(read);
        }
    }
}
