package org.rx.security;

import lombok.SneakyThrows;
import org.rx.common.App;
import org.rx.common.Contract;

import static org.rx.common.Contract.require;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.security.SecureRandom;

public class AESUtil {
    private static final String AES_ALGORITHM = "AES/ECB/PKCS5Padding";

    /**
     * 加密
     *
     * @param data 需要加密的内容
     * @param key 加密密码
     * @return
     */
    @SneakyThrows
    public static byte[] encrypt(byte[] data, byte[] key) {
        require(data, key);
        require(key, key.length == 16);

        SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        byte[] enCodeFormat = secretKey.getEncoded();
        SecretKeySpec seckey = new SecretKeySpec(enCodeFormat, "AES");
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);// 创建密码器
        cipher.init(Cipher.ENCRYPT_MODE, seckey);// 初始化
        return cipher.doFinal(data);// 加密
    }

    /**
     * 解密
     *
     * @param data 待解密内容
     * @param key 解密密钥
     * @return
     */
    @SneakyThrows
    public static byte[] decrypt(byte[] data, byte[] key) {
        require(data, key);
        require(key, key.length == 16);

        SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        byte[] enCodeFormat = secretKey.getEncoded();
        SecretKeySpec seckey = new SecretKeySpec(enCodeFormat, "AES");
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);// 创建密码器
        cipher.init(Cipher.DECRYPT_MODE, seckey);// 初始化
        return cipher.doFinal(data); // 加密
    }

    @SneakyThrows
    public static String encryptToBase64(String data, String key) {
        require(data, key);

        byte[] valueByte = encrypt(data.getBytes(Contract.Utf8), key.getBytes(Contract.Utf8));
        return App.convertToBase64String(valueByte);
    }

    @SneakyThrows
    public static String decryptFromBase64(String data, String key) {
        require(data, key);

        byte[] valueByte = decrypt(App.convertFromBase64String(data), key.getBytes(Contract.Utf8));
        return new String(valueByte, Contract.Utf8);
    }

    @SneakyThrows
    public static String encryptWithKeyBase64(String data, String key) {
        require(data, key);

        byte[] valueByte = encrypt(data.getBytes(Contract.Utf8), App.convertFromBase64String(key));
        return App.convertToBase64String(valueByte);
    }

    @SneakyThrows
    public static String decryptWithKeyBase64(String data, String key) {
        require(data, key);

        byte[] valueByte = decrypt(App.convertFromBase64String(data), App.convertFromBase64String(key));
        return new String(valueByte, Contract.Utf8);
    }

    @SneakyThrows
    public static byte[] genarateRandomKey() {
        KeyGenerator keygen = KeyGenerator.getInstance(AES_ALGORITHM);
        SecureRandom random = new SecureRandom();
        keygen.init(random);
        Key key = keygen.generateKey();
        return key.getEncoded();
    }

    public static String genarateRandomKeyWithBase64() {
        return App.convertToBase64String(genarateRandomKey());
    }
}
