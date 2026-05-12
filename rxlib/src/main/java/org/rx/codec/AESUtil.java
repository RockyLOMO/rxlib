package org.rx.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.DateTime;
import org.rx.core.Strings;
import org.rx.io.Bytes;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

//symmetrical encryption
@Slf4j
public class AESUtil {
    static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    static final int KEY_SIZE = 128;
    private static final String AES = "AES";
    private static final int KEY_LENGTH = KEY_SIZE / 8;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH = 16;
    private static final int TAG_BITS = TAG_LENGTH * 8;
    private static final FastThreadLocal<Cipher> ENC_CIPHER = new FastThreadLocal<Cipher>() {
        @Override
        protected Cipher initialValue() throws Exception {
            return Cipher.getInstance(AES_ALGORITHM);
        }
    };
    private static final FastThreadLocal<Cipher> DEC_CIPHER = new FastThreadLocal<Cipher>() {
        @Override
        protected Cipher initialValue() throws Exception {
            return Cipher.getInstance(AES_ALGORITHM);
        }
    };
    private static final FastThreadLocal<MessageDigest> KEY_DIGEST = new FastThreadLocal<MessageDigest>() {
        @Override
        protected MessageDigest initialValue() throws Exception {
            return MessageDigest.getInstance("SHA-256");
        }
    };
    private static final FastThreadLocal<byte[]> NONCE_BUF = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[NONCE_LENGTH];
        }
    };
    private static String lastDate;
    private static byte[] dateKey;

    public static byte[] dailyKey() {
        String date = DateTime.utcNow().toDateString();
        if (Strings.hashEquals(lastDate, date)) {
            return dateKey;
        }
        lastDate = date;
        return dateKey = dateKey(date);
    }

    private static byte[] dateKey(String date) {
        return String.format("℞%s", date).getBytes(StandardCharsets.UTF_8);
    }

    public static String encryptToBase64(String data) {
        return encryptToBase64(data, null);
    }

    public static String encryptToBase64(@NonNull String data, String key) {
        byte[] k = key == null ? dailyKey() : key.getBytes(StandardCharsets.UTF_8);
        byte[] valueByte = encrypt(data.getBytes(StandardCharsets.UTF_8), k);
        return CodecUtil.convertToBase64(valueByte);
    }

    public static String decryptFromBase64(String data) {
        return decryptFromBase64(data, null);
    }

    public static String decryptFromBase64(@NonNull String data, String key) {
        boolean dk = key == null;
        byte[] k = dk ? dailyKey() : key.getBytes(StandardCharsets.UTF_8);
        byte[] rawBytes = CodecUtil.convertFromBase64(data);
        byte[] valueByte;
        try {
            valueByte = decrypt(rawBytes, k);
        } catch (Exception e) {
            DateTime utcNow;
            if (dk && e instanceof BadPaddingException
                    && (utcNow = DateTime.utcNow()).getHours() == 0
                    && utcNow.getMinutes() == 0) {
                log.warn("redo decrypt");
                valueByte = decrypt(rawBytes, dateKey(utcNow.addDays(-1).toDateString()));
            } else {
                throw e;
            }
        }
        return new String(valueByte, StandardCharsets.UTF_8);
    }

    @SneakyThrows
    public static SecretKey generateKey(byte[] seed) {
        MessageDigest digest = KEY_DIGEST.get();
        digest.reset();
        byte[] hash = digest.digest(seed);
        try {
            return new SecretKeySpec(hash, 0, KEY_LENGTH, AES);
        } finally {
            Arrays.fill(hash, (byte) 0);
        }
    }

    public static byte[] generateKey(@NonNull String seed) {
        return generateKey(seed.getBytes(StandardCharsets.UTF_8)).getEncoded();
    }

    @SneakyThrows
    public static ByteBuf encrypt(@NonNull ByteBuf buf, byte[] key) {
        Cipher cipher = ENC_CIPHER.get();
        byte[] nonce = randomNonce();
        initCipher(cipher, Cipher.ENCRYPT_MODE, key, nonce);
        ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer(NONCE_LENGTH + cipher.getOutputSize(buf.readableBytes()));
        try {
            out.writeBytes(nonce, 0, NONCE_LENGTH);
            applyCipher(cipher, buf, buf.readerIndex(), buf.readableBytes(), out);
            return out;
        } catch (Throwable e) {
            out.release();
            throw e;
        }
    }

    @SneakyThrows
    public static ByteBuf decrypt(@NonNull ByteBuf buf, byte[] key) {
        int readable = buf.readableBytes();
        checkInputLength(readable);
        Cipher cipher = DEC_CIPHER.get();
        byte[] nonce = NONCE_BUF.get();
        int readerIndex = buf.readerIndex();
        buf.getBytes(readerIndex, nonce, 0, NONCE_LENGTH);
        initCipher(cipher, Cipher.DECRYPT_MODE, key, nonce);
        ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer(Math.max(0, cipher.getOutputSize(readable - NONCE_LENGTH)));
        try {
            applyCipher(cipher, buf, readerIndex + NONCE_LENGTH, readable - NONCE_LENGTH, out);
            buf.skipBytes(readable);
            return out;
        } catch (Throwable e) {
            out.release();
            throw e;
        }
    }

    @SneakyThrows
    public static ByteBuf encrypt(@NonNull ByteBuf buf, byte[] key, @NonNull ByteBuf out) {
        Cipher cipher = ENC_CIPHER.get();
        byte[] nonce = randomNonce();
        initCipher(cipher, Cipher.ENCRYPT_MODE, key, nonce);
        out.ensureWritable(NONCE_LENGTH + cipher.getOutputSize(buf.readableBytes()));
        out.writeBytes(nonce, 0, NONCE_LENGTH);
        applyCipher(cipher, buf, buf.readerIndex(), buf.readableBytes(), out);
        return out;
    }

    @SneakyThrows
    public static ByteBuf decrypt(@NonNull ByteBuf buf, byte[] key, @NonNull ByteBuf out) {
        int readable = buf.readableBytes();
        checkInputLength(readable);
        Cipher cipher = DEC_CIPHER.get();
        byte[] nonce = NONCE_BUF.get();
        int readerIndex = buf.readerIndex();
        buf.getBytes(readerIndex, nonce, 0, NONCE_LENGTH);
        initCipher(cipher, Cipher.DECRYPT_MODE, key, nonce);
        applyCipher(cipher, buf, readerIndex + NONCE_LENGTH, readable - NONCE_LENGTH, out);
        buf.skipBytes(readable);
        return out;
    }

    @SneakyThrows
    public static byte[] encrypt(byte[] data, byte[] key) {
        Cipher cipher = ENC_CIPHER.get();
        byte[] nonce = randomNonce();
        initCipher(cipher, Cipher.ENCRYPT_MODE, key, nonce);
        byte[] out = new byte[NONCE_LENGTH + cipher.getOutputSize(data.length)];
        System.arraycopy(nonce, 0, out, 0, NONCE_LENGTH);
        int written = cipher.doFinal(data, 0, data.length, out, NONCE_LENGTH);
        return trim(out, NONCE_LENGTH + written);
    }

    @SneakyThrows
    public static byte[] decrypt(byte[] data, byte[] key) {
        checkInputLength(data.length);
        Cipher cipher = DEC_CIPHER.get();
        initCipher(cipher, Cipher.DECRYPT_MODE, key, data, 0);
        byte[] out = new byte[Math.max(0, cipher.getOutputSize(data.length - NONCE_LENGTH))];
        int written = cipher.doFinal(data, NONCE_LENGTH, data.length - NONCE_LENGTH, out, 0);
        return trim(out, written);
    }

    private static void initCipher(Cipher cipher, int mode, byte[] key, byte[] nonce) throws Exception {
        cipher.init(mode, generateKey(key), new GCMParameterSpec(TAG_BITS, nonce));
    }

    private static void initCipher(Cipher cipher, int mode, byte[] key, byte[] nonce, int nonceOffset) throws Exception {
        cipher.init(mode, generateKey(key), new GCMParameterSpec(TAG_BITS, nonce, nonceOffset, NONCE_LENGTH));
    }

    private static byte[] randomNonce() {
        byte[] nonce = NONCE_BUF.get();
        CodecUtil.threadLocalSecureRandom().nextBytes(nonce);
        return nonce;
    }

    private static void checkInputLength(int length) {
        if (length < NONCE_LENGTH + TAG_LENGTH) {
            throw new IllegalArgumentException("AES-GCM input too short");
        }
    }

    private static byte[] trim(byte[] out, int length) {
        return length == out.length ? out : Arrays.copyOf(out, length);
    }

    private static void applyCipher(Cipher cipher, ByteBuf in, int index, int length, ByteBuf out) throws Exception {
        out.ensureWritable(cipher.getOutputSize(length));
        int writerIndex = out.writerIndex();
        ByteBuffer output = out.nioBuffer(writerIndex, out.writableBytes());
        int start = output.position();
        if (length == 0) {
            cipher.doFinal(Bytes.EMPTY_NIO, output);
        } else if (in.nioBufferCount() == 1) {
            cipher.doFinal(in.nioBuffer(index, length), output);
        } else {
            ByteBuffer[] buffers = in.nioBuffers(index, length);
            for (ByteBuffer buffer : buffers) {
                if (!buffer.hasRemaining()) {
                    continue;
                }
                cipher.update(buffer, output);
            }
            cipher.doFinal(Bytes.EMPTY_NIO, output);
        }
        out.writerIndex(writerIndex + output.position() - start);
    }
}
