package org.rx.net.socks.encryption.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.DecoderException;
import io.netty.util.concurrent.FastThreadLocal;
import org.rx.codec.CodecUtil;
import org.rx.io.Bytes;
import org.rx.net.socks.encryption.ICrypto;
import org.rx.net.socks.encryption.ShadowSocksKey;

import javax.crypto.AEADBadTagException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

public class ChaCha20Poly1305ByteBufCrypto implements ICrypto, AutoCloseable {
    public static final String AEAD_CHACHA20_POLY1305 = "chacha20-ietf-poly1305";
    private static final String HKDF_ALGORITHM = "HmacSHA1";
    private static final byte[] INFO = "ss-subkey".getBytes(StandardCharsets.US_ASCII);
    private static final int KEY_LENGTH = 32;
    private static final int SALT_LENGTH = 32;
    private static final int TAG_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int PAYLOAD_SIZE_MASK = 0x3FFF;
    private static final int LEN_SIZE = 2;
    private static final int LEN_TAG_SIZE = LEN_SIZE + TAG_LENGTH;
    private static final byte[] ZERO_NONCE = new byte[NONCE_LENGTH];

    private static final FastThreadLocal<Mac> HKDF_MAC = new FastThreadLocal<Mac>() {
        @Override
        protected Mac initialValue() throws Exception {
            return Mac.getInstance(HKDF_ALGORITHM);
        }
    };
    private static final FastThreadLocal<byte[]> SALT_BUF = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[SALT_LENGTH];
        }
    };
    private static final FastThreadLocal<byte[]> HKDF_BLOCK = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[20];
        }
    };

    private final ShadowSocksKey ssKey;
    private final boolean directBuf;
    private boolean forUdp;
    private byte[] encSubkey;
    private byte[] decSubkey;
    private byte[] encNonce;
    private byte[] decNonce;
    private final byte[] encLenBytes = new byte[LEN_SIZE];
    private final byte[] decLenBytes = new byte[LEN_SIZE];
    private final ByteBuffer encLenBuffer = ByteBuffer.wrap(encLenBytes);
    private final ByteBuffer decLenBuffer = ByteBuffer.wrap(decLenBytes);
    private boolean encSessionStarted;
    private boolean decSessionStarted;
    private boolean readingLengthPhase = true;
    private int saltBytesRead;
    private int currentPayloadLength;
    private int phaseBytesRead;
    private ByteBuf pendingEncrypted;
    private boolean closed;

    public ChaCha20Poly1305ByteBufCrypto(String name, String password) {
        this(name, password, true);
    }

    public ChaCha20Poly1305ByteBufCrypto(String name, String password, boolean directBuf) {
        if (!AEAD_CHACHA20_POLY1305.equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("Unsupported cipher: " + name);
        }
        this.ssKey = new ShadowSocksKey(password, KEY_LENGTH);
        this.directBuf = directBuf;
    }

    @Override
    public void setForUdp(boolean forUdp) {
        this.forUdp = forUdp;
        if (!forUdp && encNonce == null && decNonce == null) {
            encNonce = new byte[NONCE_LENGTH];
            decNonce = new byte[NONCE_LENGTH];
        }
    }

    @Override
    public ByteBuf encrypt(ByteBuf in) {
        checkOpen();
        int readable = in.readableBytes();
        int chunks = readable == 0 ? 0 : (readable + PAYLOAD_SIZE_MASK - 1) / PAYLOAD_SIZE_MASK;
        int estimatedSize = forUdp
                ? SALT_LENGTH + readable + TAG_LENGTH
                : (!encSessionStarted ? SALT_LENGTH : 0) + readable + chunks * (LEN_TAG_SIZE + TAG_LENGTH);
        ByteBuf out = allocate(estimatedSize);
        try {
            if (forUdp) {
                encryptUdp(in, out);
            } else {
                encryptTcp(in, out);
            }
            return out;
        } catch (Throwable e) {
            Bytes.release(out);
            throw e;
        }
    }

    @Override
    public ByteBuf decrypt(ByteBuf in) {
        checkOpen();
        int estimatedSize;
        if (forUdp) {
            estimatedSize = Math.max(0, in.readableBytes() - SALT_LENGTH - TAG_LENGTH);
        } else {
            estimatedSize = !decSessionStarted ? Math.max(0, in.readableBytes() - SALT_LENGTH) : in.readableBytes();
        }
        ByteBuf out = allocate(estimatedSize);
        try {
            if (forUdp) {
                decryptUdp(in, out);
            } else {
                decryptTcp(in, out);
            }
            return out;
        } catch (Throwable e) {
            Bytes.release(out);
            throw e;
        }
    }

    private void encryptTcp(ByteBuf in, ByteBuf out) {
        try {
            if (!encSessionStarted) {
                byte[] salt = CodecUtil.secureRandomBytes(SALT_LENGTH);
                out.writeBytes(salt);
                encSubkey = genSubkey(salt);
                encSessionStarted = true;
            }
            while (in.isReadable()) {
                int chunkSize = Math.min(in.readableBytes(), PAYLOAD_SIZE_MASK);
                encLenBytes[0] = (byte) (chunkSize >>> 8);
                encLenBytes[1] = (byte) chunkSize;
                encLenBuffer.clear();
                ChaCha20Poly1305Support.encrypt(encSubkey, encNonce, encLenBuffer, LEN_SIZE, out);
                increment(encNonce);

                int readerIndex = in.readerIndex();
                ChaCha20Poly1305Support.encrypt(encSubkey, encNonce, in, readerIndex, chunkSize, out);
                in.readerIndex(readerIndex + chunkSize);
                increment(encNonce);
            }
        } catch (GeneralSecurityException e) {
            throw new DecoderException("ChaCha20-Poly1305 TCP encrypt failed", e);
        }
    }

    private void decryptTcp(ByteBuf in, ByteBuf out) {
        try {
            if (!decSessionStarted) {
                if (!readTcpSalt(in)) {
                    return;
                }
            }
            while (in.isReadable()) {
                int phaseSize = readingLengthPhase ? LEN_TAG_SIZE : currentPayloadLength + TAG_LENGTH;
                if (phaseBytesRead == 0 && in.readableBytes() >= phaseSize) {
                    int readerIndex = in.readerIndex();
                    decryptTcpPhase(in, readerIndex, phaseSize, out);
                    in.readerIndex(readerIndex + phaseSize);
                    continue;
                }

                ByteBuf pending = pendingEncrypted();
                int readNow = Math.min(phaseSize - phaseBytesRead, in.readableBytes());
                pending.writeBytes(in, in.readerIndex(), readNow);
                in.skipBytes(readNow);
                phaseBytesRead += readNow;
                if (phaseBytesRead < phaseSize) {
                    return;
                }

                decryptTcpPhase(pending, 0, phaseSize, out);
                pending.clear();
                phaseBytesRead = 0;
            }
        } catch (GeneralSecurityException e) {
            throw new DecoderException("ChaCha20-Poly1305 TCP decrypt failed", e);
        }
    }

    private boolean readTcpSalt(ByteBuf in) throws GeneralSecurityException {
        byte[] salt = SALT_BUF.get();
        if (saltBytesRead == 0 && in.readableBytes() >= SALT_LENGTH) {
            in.readBytes(salt, 0, SALT_LENGTH);
        } else {
            int readNow = Math.min(SALT_LENGTH - saltBytesRead, in.readableBytes());
            if (readNow <= 0) {
                return false;
            }
            ByteBuf pending = pendingEncrypted();
            pending.writeBytes(in, in.readerIndex(), readNow);
            in.skipBytes(readNow);
            saltBytesRead += readNow;
            if (saltBytesRead < SALT_LENGTH) {
                return false;
            }
            pending.getBytes(0, salt, 0, SALT_LENGTH);
            pending.clear();
            saltBytesRead = 0;
        }
        decSubkey = genSubkey(salt);
        decSessionStarted = true;
        resetDecryptState();
        return true;
    }

    private void decryptTcpPhase(ByteBuf in, int index, int length, ByteBuf out) throws AEADBadTagException {
        if (readingLengthPhase) {
            decLenBuffer.clear();
            int written = ChaCha20Poly1305Support.decrypt(decSubkey, decNonce, in, index, length, decLenBuffer);
            if (written != LEN_SIZE) {
                throw new DecoderException("Invalid length chunk");
            }
            increment(decNonce);
            currentPayloadLength = ((decLenBytes[0] & 0xFF) << 8) | (decLenBytes[1] & 0xFF);
            if (currentPayloadLength > PAYLOAD_SIZE_MASK) {
                throw new DecoderException("Payload too large: " + currentPayloadLength);
            }
            readingLengthPhase = false;
            return;
        }

        int written = ChaCha20Poly1305Support.decrypt(decSubkey, decNonce, in, index, length, out);
        if (written != currentPayloadLength) {
            throw new DecoderException("Invalid payload chunk");
        }
        increment(decNonce);
        readingLengthPhase = true;
        currentPayloadLength = 0;
    }

    private void encryptUdp(ByteBuf in, ByteBuf out) {
        try {
            byte[] salt = CodecUtil.secureRandomBytes(SALT_LENGTH);
            out.writeBytes(salt);
            byte[] subkey = genSubkey(salt);
            int len = in.readableBytes();
            int readerIndex = in.readerIndex();
            ChaCha20Poly1305Support.encrypt(subkey, ZERO_NONCE, in, readerIndex, len, out);
            in.readerIndex(readerIndex + len);
        } catch (GeneralSecurityException e) {
            throw new DecoderException("ChaCha20-Poly1305 UDP encrypt failed", e);
        }
    }

    private void decryptUdp(ByteBuf in, ByteBuf out) {
        try {
            if (in.readableBytes() < SALT_LENGTH + TAG_LENGTH) {
                throw new DecoderException("Packet too short");
            }
            byte[] salt = SALT_BUF.get();
            in.readBytes(salt, 0, SALT_LENGTH);
            byte[] subkey = genSubkey(salt);
            int len = in.readableBytes();
            int readerIndex = in.readerIndex();
            ChaCha20Poly1305Support.decrypt(subkey, ZERO_NONCE, in, readerIndex, len, out);
            in.readerIndex(readerIndex + len);
        } catch (GeneralSecurityException e) {
            throw new DecoderException("ChaCha20-Poly1305 UDP decrypt failed", e);
        }
    }

    private byte[] genSubkey(byte[] salt) throws GeneralSecurityException {
        Mac mac = HKDF_MAC.get();
        byte[] prk = mac(HKDF_ALGORITHM, salt, ssKey.getEncoded(), mac);
        byte[] okm = new byte[KEY_LENGTH];
        byte[] block = HKDF_BLOCK.get();
        int copied = 0;
        int counter = 1;
        int blockLen = 0;
        while (copied < KEY_LENGTH) {
            mac.init(new SecretKeySpec(prk, HKDF_ALGORITHM));
            if (blockLen > 0) {
                mac.update(block, 0, blockLen);
            }
            mac.update(INFO);
            mac.update((byte) counter++);
            mac.doFinal(block, 0);
            blockLen = mac.getMacLength();
            int copy = Math.min(blockLen, KEY_LENGTH - copied);
            System.arraycopy(block, 0, okm, copied, copy);
            copied += copy;
        }
        Arrays.fill(prk, (byte) 0);
        return okm;
    }

    private byte[] mac(String algorithm, byte[] key, byte[] data, Mac mac) throws GeneralSecurityException {
        mac.init(new SecretKeySpec(key, algorithm));
        return mac.doFinal(data);
    }

    private ByteBuf allocate(int initialCapacity) {
        return directBuf ? Bytes.directBuffer(initialCapacity) : PooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity);
    }

    private ByteBuf pendingEncrypted() {
        if (pendingEncrypted == null) {
            pendingEncrypted = allocate(PAYLOAD_SIZE_MASK + TAG_LENGTH);
        }
        return pendingEncrypted;
    }

    private void resetDecryptState() {
        readingLengthPhase = true;
        currentPayloadLength = 0;
        phaseBytesRead = 0;
        if (pendingEncrypted != null) {
            pendingEncrypted.clear();
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("crypto closed");
        }
    }

    @Override
    public void close() {
        closed = true;
        Bytes.release(pendingEncrypted);
        pendingEncrypted = null;
        fill(encSubkey);
        fill(decSubkey);
        fill(encNonce);
        fill(decNonce);
    }

    private static void fill(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static void increment(byte[] nonce) {
        for (int i = 0; i < nonce.length; i++) {
            if (++nonce[i] != 0) {
                break;
            }
        }
    }
}
