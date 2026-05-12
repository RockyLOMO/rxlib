package org.rx.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.DateTime;
import org.rx.core.Strings;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

//symmetrical encryption
@Slf4j
public class AESUtil {
    static final String AES_ALGORITHM = "AES/ECB/PKCS5Padding";
    static final int KEY_SIZE = 128; //256
    private static final FastThreadLocal<ByteBuffer> EMPTY_NIO = new FastThreadLocal<ByteBuffer>() {
        @Override
        protected ByteBuffer initialValue() {
            return ByteBuffer.allocate(0);
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
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(seed);
        KeyGenerator keygen = KeyGenerator.getInstance("AES");
        keygen.init(KEY_SIZE, random);
        return keygen.generateKey();
    }

    @SneakyThrows
    public static ByteBuf encrypt(@NonNull ByteBuf buf, byte[] key) {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, generateKey(key));
        ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer(cipher.getOutputSize(buf.readableBytes()));
        try {
            applyCipher(cipher, buf, out, false);
            return out;
        } catch (Throwable e) {
            out.release();
            throw e;
        }
    }

    @SneakyThrows
    public static ByteBuf decrypt(@NonNull ByteBuf buf, byte[] key) {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, generateKey(key));
        ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer(cipher.getOutputSize(buf.readableBytes()));
        try {
            applyCipher(cipher, buf, out, true);
            return out;
        } catch (Throwable e) {
            out.release();
            throw e;
        }
    }

    @SneakyThrows
    public static ByteBuf encrypt(@NonNull ByteBuf buf, byte[] key, @NonNull ByteBuf out) {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, generateKey(key));
        applyCipher(cipher, buf, out, false);
        return out;
    }

    @SneakyThrows
    public static ByteBuf decrypt(@NonNull ByteBuf buf, byte[] key, @NonNull ByteBuf out) {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, generateKey(key));
        applyCipher(cipher, buf, out, true);
        return out;
    }

    @SneakyThrows
    public static byte[] encrypt(byte[] data, byte[] key) {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, generateKey(key));
        return cipher.doFinal(data);
    }

    @SneakyThrows
    public static byte[] decrypt(byte[] data, byte[] key) {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, generateKey(key));
        return cipher.doFinal(data);
    }

    private static void applyCipher(Cipher cipher, ByteBuf in, ByteBuf out, boolean consume) throws Exception {
        int length = in.readableBytes();
        out.ensureWritable(cipher.getOutputSize(length));
        int writerIndex = out.writerIndex();
        ByteBuffer output = out.nioBuffer(writerIndex, out.writableBytes());
        int start = output.position();
        if (length == 0) {
            ByteBuffer empty = EMPTY_NIO.get();
            empty.clear();
            cipher.doFinal(empty, output);
        } else if (in.nioBufferCount() == 1) {
            cipher.doFinal(in.nioBuffer(in.readerIndex(), length), output);
        } else {
            ByteBuffer[] buffers = in.nioBuffers(in.readerIndex(), length);
            for (ByteBuffer buffer : buffers) {
                if (!buffer.hasRemaining()) {
                    continue;
                }
                cipher.update(buffer, output);
            }
            ByteBuffer empty = EMPTY_NIO.get();
            empty.clear();
            cipher.doFinal(empty, output);
        }
        out.writerIndex(writerIndex + output.position() - start);
        if (consume) {
            in.skipBytes(length);
        }
    }
}
