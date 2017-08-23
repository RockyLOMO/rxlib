package org.rx.security;

import org.rx.util.App;

import static org.rx.common.Contract.require;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public class AESUtil extends App {
    private static final String AES_ALGORITHM = "AES/ECB/PKCS5Padding";

    /**
     * 加密
     *
     * @param data 需要加密的内容
     * @param key 加密密码
     * @return
     */
    public static byte[] encrypt(byte[] data, byte[] key) throws GeneralSecurityException {
        require(data, key);
        require(key, p -> p.length == 16);

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
    public static byte[] decrypt(byte[] data, byte[] key) throws GeneralSecurityException {
        require(data, key);
        require(key, p -> p.length == 16);

        SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        byte[] enCodeFormat = secretKey.getEncoded();
        SecretKeySpec seckey = new SecretKeySpec(enCodeFormat, "AES");
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);// 创建密码器
        cipher.init(Cipher.DECRYPT_MODE, seckey);// 初始化
        return cipher.doFinal(data); // 加密
    }

    public static String encryptToBase64(String data, String key) throws GeneralSecurityException {
        require(data, key);

        try {
            byte[] valueByte = encrypt(data.getBytes(UTF8), key.getBytes(UTF8));
            return convertToBase64String(valueByte);
        } catch (Exception ex) {
            throw new GeneralSecurityException(String.format("Encrypt fail! key=%s data=%s", key, data), ex);
        }
    }

    public static String decryptFromBase64(String data, String key) throws GeneralSecurityException {
        require(data, key);

        try {
            byte[] valueByte = decrypt(convertFromBase64String(data), key.getBytes(UTF8));
            return new String(valueByte, UTF8);
        } catch (Exception ex) {
            throw new GeneralSecurityException(String.format("Decrypt fail! key=%s data=%s", key, data), ex);
        }
    }

    public static String encryptWithKeyBase64(String data, String key) throws GeneralSecurityException {
        require(data, key);

        try {
            byte[] valueByte = encrypt(data.getBytes(UTF8), convertFromBase64String(key));
            return convertToBase64String(valueByte);
        } catch (Exception ex) {
            throw new GeneralSecurityException(String.format("Encrypt fail! key=%s data=%s", key, data), ex);
        }
    }

    public static String decryptWithKeyBase64(String data, String key) throws GeneralSecurityException {
        require(data, key);

        try {
            byte[] valueByte = decrypt(convertFromBase64String(data), convertFromBase64String(key));
            return new String(valueByte, UTF8);
        } catch (Exception ex) {
            throw new GeneralSecurityException(String.format("Decrypt fail! key=%s data=%s", key, data), ex);
        }
    }

    public static byte[] genarateRandomKey() {
        KeyGenerator keygen;
        try {
            keygen = KeyGenerator.getInstance(AES_ALGORITHM);
        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException("genarateRandomKey fail!", ex);
        }
        SecureRandom random = new SecureRandom();
        keygen.init(random);
        Key key = keygen.generateKey();
        return key.getEncoded();
    }

    public static String genarateRandomKeyWithBase64() {
        return convertToBase64String(genarateRandomKey());
    }
}
