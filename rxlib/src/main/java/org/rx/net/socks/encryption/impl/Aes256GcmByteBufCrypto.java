package org.rx.net.socks.encryption.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.DecoderException;
import io.netty.util.concurrent.FastThreadLocal;
import org.rx.codec.CodecUtil;
import org.rx.io.Bytes;
import org.rx.net.socks.encryption.ICrypto;
import org.rx.net.socks.encryption.ShadowSocksKey;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

public class Aes256GcmByteBufCrypto implements ICrypto, AutoCloseable {
    public static final String AEAD_AES_256_GCM = "aes-256-gcm";
    private static final String CIPHER_NAME = "AES/GCM/NoPadding";
    private static final String AES = "AES";
    private static final String HKDF_ALGORITHM = "HmacSHA1";
    private static final byte[] INFO = "ss-subkey".getBytes(StandardCharsets.US_ASCII);
    private static final int KEY_LENGTH = 32;
    private static final int SALT_LENGTH = 32;
    private static final int TAG_LENGTH = 16;
    private static final int TAG_BITS = TAG_LENGTH * 8;
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
    private static final FastThreadLocal<ByteBuffer> EMPTY_NIO = new FastThreadLocal<ByteBuffer>() {
        @Override
        protected ByteBuffer initialValue() {
            return ByteBuffer.allocate(0);
        }
    };
    private static final FastThreadLocal<byte[]> HKDF_BLOCK = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[20];
        }
    };
    private static final FastThreadLocal<Cipher> UDP_ENC_CIPHER = new FastThreadLocal<Cipher>() {
        @Override
        protected Cipher initialValue() throws Exception {
            return Cipher.getInstance(CIPHER_NAME);
        }
    };
    private static final FastThreadLocal<Cipher> UDP_DEC_CIPHER = new FastThreadLocal<Cipher>() {
        @Override
        protected Cipher initialValue() throws Exception {
            return Cipher.getInstance(CIPHER_NAME);
        }
    };

    private final ShadowSocksKey ssKey;
    private final boolean directBuf;
    private boolean forUdp;

    private Cipher encCipher;
    private Cipher decCipher;
    private byte[] encSubkey;
    private byte[] decSubkey;
    private byte[] encNonce;
    private byte[] decNonce;

    private final byte[] encLenBytes = new byte[LEN_SIZE];
    private final byte[] decLenBytes = new byte[LEN_SIZE];
    private final ByteBuffer encLenBuffer = ByteBuffer.wrap(encLenBytes);
    private final ByteBuffer decLenBuffer = ByteBuffer.wrap(decLenBytes);

    private boolean readingLengthPhase = true;
    private int currentPayloadLength;
    private int phaseBytesRead;
    private ByteBuf pendingEncrypted;
    private boolean closed;

    public Aes256GcmByteBufCrypto(String name, String password) {
        this(name, password, true);
    }

    public Aes256GcmByteBufCrypto(String name, String password, boolean directBuf) {
        if (!AEAD_AES_256_GCM.equalsIgnoreCase(name)) {
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
                : (encCipher == null ? SALT_LENGTH : 0) + readable + chunks * (LEN_TAG_SIZE + TAG_LENGTH);
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
            estimatedSize = decCipher == null
                    ? Math.max(0, in.readableBytes() - SALT_LENGTH)
                    : in.readableBytes();
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
            if (encCipher == null) {
                byte[] salt = CodecUtil.secureRandomBytes(SALT_LENGTH);
                out.writeBytes(salt);
                encSubkey = genSubkey(salt);
                encCipher = Cipher.getInstance(CIPHER_NAME);
            }

            while (in.isReadable()) {
                int chunkSize = Math.min(in.readableBytes(), PAYLOAD_SIZE_MASK);
                encLenBytes[0] = (byte) (chunkSize >>> 8);
                encLenBytes[1] = (byte) chunkSize;
                initCipher(encCipher, Cipher.ENCRYPT_MODE, encSubkey, encNonce);
                encLenBuffer.clear();
                applyCipher(encCipher, encLenBuffer, out, LEN_TAG_SIZE);
                increment(encNonce);

                int readerIndex = in.readerIndex();
                initCipher(encCipher, Cipher.ENCRYPT_MODE, encSubkey, encNonce);
                applyCipher(encCipher, in, readerIndex, chunkSize, out, chunkSize + TAG_LENGTH);
                in.readerIndex(readerIndex + chunkSize);
                increment(encNonce);
            }
        } catch (GeneralSecurityException e) {
            throw new DecoderException("AES-256-GCM TCP encrypt failed", e);
        }
    }

    private void decryptTcp(ByteBuf in, ByteBuf out) {
        try {
            if (decCipher == null) {
                if (in.readableBytes() < SALT_LENGTH + TAG_LENGTH) {
                    throw new DecoderException("Packet too short");
                }
                byte[] salt = SALT_BUF.get();
                in.readBytes(salt, 0, SALT_LENGTH);
                decSubkey = genSubkey(salt);
                decCipher = Cipher.getInstance(CIPHER_NAME);
                resetDecryptState();
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
            throw new DecoderException("AES-256-GCM TCP decrypt failed", e);
        }
    }

    private void decryptTcpPhase(ByteBuf in, int index, int length, ByteBuf out) throws GeneralSecurityException {
        if (readingLengthPhase) {
            initCipher(decCipher, Cipher.DECRYPT_MODE, decSubkey, decNonce);
            decLenBuffer.clear();
            int written = applyCipher(decCipher, in, index, length, decLenBuffer);
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

        initCipher(decCipher, Cipher.DECRYPT_MODE, decSubkey, decNonce);
        int written = applyCipher(decCipher, in, index, length, out, currentPayloadLength);
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
            Cipher cipher = UDP_ENC_CIPHER.get();
            initCipher(cipher, Cipher.ENCRYPT_MODE, subkey, ZERO_NONCE);
            int len = in.readableBytes();
            int readerIndex = in.readerIndex();
            applyCipher(cipher, in, readerIndex, len, out, len + TAG_LENGTH);
            in.readerIndex(readerIndex + len);
        } catch (GeneralSecurityException e) {
            throw new DecoderException("AES-256-GCM UDP encrypt failed", e);
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
            Cipher cipher = UDP_DEC_CIPHER.get();
            initCipher(cipher, Cipher.DECRYPT_MODE, subkey, ZERO_NONCE);
            int len = in.readableBytes();
            int readerIndex = in.readerIndex();
            applyCipher(cipher, in, readerIndex, len, out, Math.max(0, len - TAG_LENGTH));
            in.readerIndex(readerIndex + len);
        } catch (GeneralSecurityException e) {
            throw new DecoderException("AES-256-GCM UDP decrypt failed", e);
        }
    }

    private int applyCipher(Cipher cipher, ByteBuffer input, ByteBuffer output) throws GeneralSecurityException {
        int start = output.position();
        cipher.update(input, output);
        ByteBuffer empty = EMPTY_NIO.get();
        empty.clear();
        cipher.doFinal(empty, output);
        return output.position() - start;
    }

    private int applyCipher(Cipher cipher, ByteBuffer input, ByteBuf out, int maxOutputLength)
            throws GeneralSecurityException {
        out.ensureWritable(maxOutputLength);
        int writerIndex = out.writerIndex();
        ByteBuffer output = out.nioBuffer(writerIndex, maxOutputLength);
        int written = applyCipher(cipher, input, output);
        out.writerIndex(writerIndex + written);
        return written;
    }

    private int applyCipher(Cipher cipher, ByteBuf in, int index, int length, ByteBuf out, int maxOutputLength)
            throws GeneralSecurityException {
        out.ensureWritable(maxOutputLength);
        int writerIndex = out.writerIndex();
        ByteBuffer output = out.nioBuffer(writerIndex, maxOutputLength);
        int written = applyCipher(cipher, in, index, length, output);
        out.writerIndex(writerIndex + written);
        return written;
    }

    private int applyCipher(Cipher cipher, ByteBuf in, int index, int length, ByteBuffer output)
            throws GeneralSecurityException {
        int start = output.position();
        if (length > 0) {
            if (in.nioBufferCount() == 1) {
                cipher.update(in.nioBuffer(index, length), output);
            } else {
                ByteBuffer[] buffers = in.nioBuffers(index, length);
                for (ByteBuffer buffer : buffers) {
                    if (buffer.hasRemaining()) {
                        cipher.update(buffer, output);
                    }
                }
            }
        }
        ByteBuffer empty = EMPTY_NIO.get();
        empty.clear();
        cipher.doFinal(empty, output);
        return output.position() - start;
    }

    private void initCipher(Cipher cipher, int mode, byte[] key, byte[] nonce) throws GeneralSecurityException {
        cipher.init(mode, new SecretKeySpec(key, AES), new GCMParameterSpec(TAG_BITS, nonce));
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
        return directBuf
                ? Bytes.directBuffer(initialCapacity)
                : PooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity);
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
