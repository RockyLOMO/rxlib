package org.rx.security;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.core.App;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

//对称加密
public class AESUtil {
    private static final String AES_ALGORITHM = "AES/ECB/PKCS5Padding";

    public static String encryptToBase64(@NonNull String data, @NonNull String key) {
        byte[] valueByte = encrypt(data.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
        return App.convertToBase64String(valueByte);
    }

    public static String decryptFromBase64(@NonNull String data, @NonNull String key) {
        byte[] valueByte = decrypt(App.convertFromBase64String(data), key.getBytes(StandardCharsets.UTF_8));
        return new String(valueByte, StandardCharsets.UTF_8);
    }

    public static String encryptWithKeyBase64(@NonNull String data, @NonNull String key) {
        byte[] valueByte = encrypt(data.getBytes(StandardCharsets.UTF_8), App.convertFromBase64String(key));
        return App.convertToBase64String(valueByte);
    }

    public static String decryptWithKeyBase64(@NonNull String data, @NonNull String key) {
        byte[] valueByte = decrypt(App.convertFromBase64String(data), App.convertFromBase64String(key));
        return new String(valueByte, StandardCharsets.UTF_8);
    }

    @SneakyThrows
    public static SecretKey generateKey(byte[] seed) {
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(seed);
        KeyGenerator keygen = KeyGenerator.getInstance("AES");
        keygen.init(256, random);
        return keygen.generateKey();
    }

    @SneakyThrows
    public static ByteBuf encrypt(@NonNull ByteBuf buf, @NonNull byte[] key) {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, generateKey(key));
        ByteBuffer in = buf.nioBuffer();
        int outputSize = cipher.getOutputSize(in.remaining());
        ByteBuffer out = ByteBuffer.allocateDirect(outputSize);
        cipher.doFinal(in, out);
        return Unpooled.wrappedBuffer((ByteBuffer) out.flip());
    }

    @SneakyThrows
    public static ByteBuf decrypt(@NonNull ByteBuf buf, @NonNull byte[] key) {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        SecretKey secretKey = generateKey(key);
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        ByteBuffer in = buf.nioBuffer();
        int outputSize = cipher.getOutputSize(in.remaining());
        ByteBuffer out = ByteBuffer.allocateDirect(outputSize);
        cipher.doFinal(in, out);
        return Unpooled.wrappedBuffer((ByteBuffer) out.flip());
    }

    @SneakyThrows
    public static byte[] encrypt(@NonNull byte[] data, @NonNull byte[] key) {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, generateKey(key));
        return cipher.doFinal(data);
    }

    @SneakyThrows
    public static byte[] decrypt(@NonNull byte[] data, @NonNull byte[] key) {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, generateKey(key));
        return cipher.doFinal(data);
    }
}
