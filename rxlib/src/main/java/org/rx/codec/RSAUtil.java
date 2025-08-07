package org.rx.codec;

import lombok.NonNull;
import lombok.SneakyThrows;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import java.util.TreeMap;

//asymmetric encryption
public final class RSAUtil {
    static final String SIGN_ALGORITHMS = "MD5withRSA";
    static final String SIGN_ALGORITHMS2 = "SHA1WithRSA";
    static final String RSA_ALGORITHM = "RSA/ECB/PKCS1Padding";

    @SneakyThrows
    public static String[] generateKeyPair() {
        KeyPairGenerator keygen = KeyPairGenerator.getInstance("RSA");
        keygen.initialize(1024, CodecUtil.threadLocalSecureRandom());
        KeyPair keys = keygen.genKeyPair();
        PublicKey pubkey = keys.getPublic();
        PrivateKey prikey = keys.getPrivate();

        String pubKeyStr = CodecUtil.convertToBase64(pubkey.getEncoded());
        String priKeyStr = CodecUtil.convertToBase64(prikey.getEncoded());
        return new String[]{pubKeyStr, priKeyStr};
    }

    public static String sign(@NonNull TreeMap<String, Object> map, String privateKey) {
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            content.append(entry.getValue());
        }
        return sign(content.toString(), privateKey);
    }

    public static String sign(String content, String privateKey) {
        return sign(content, privateKey, false);
    }

    /**
     * 使用{@code RSA}方式对字符串进行签名
     *
     * @param content    需要加签名的数据
     * @param privateKey {@code RSA}的私钥
     * @param isSHA1     数据的编码方式
     * @return 返回签名信息
     */
    @SneakyThrows
    public static String sign(@NonNull String content, @NonNull String privateKey, boolean isSHA1) {
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(CodecUtil.convertFromBase64(privateKey));
        KeyFactory keyf = KeyFactory.getInstance("RSA");
        PrivateKey priKey = keyf.generatePrivate(keySpec);

        Signature signature = Signature.getInstance(isSHA1 ? SIGN_ALGORITHMS2 : SIGN_ALGORITHMS);
        signature.initSign(priKey);
        signature.update(content.getBytes(StandardCharsets.UTF_8));
        return CodecUtil.convertToBase64(signature.sign());
    }

    public static boolean verify(@NonNull TreeMap<String, Object> map, String sign, String publicKey) {
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            content.append(entry.getValue());
        }
        return verify(content.toString(), sign, publicKey);
    }

    public static boolean verify(String content, String sign, String publicKey) {
        return verify(content, sign, publicKey, false);
    }

    /**
     * 使用{@code RSA}方式对签名信息进行验证
     *
     * @param content   需要加签名的数据
     * @param sign      签名信息
     * @param publicKey {@code RSA}的公钥
     * @param isSHA1    数据的编码方式
     * @return 是否验证通过。{@code True}表示通过
     */
    @SneakyThrows
    public static boolean verify(@NonNull String content, @NonNull String sign, @NonNull String publicKey, boolean isSHA1) {
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PublicKey pubKey = keyFactory.generatePublic(new X509EncodedKeySpec(CodecUtil.convertFromBase64(publicKey)));

        Signature signature = Signature.getInstance(isSHA1 ? SIGN_ALGORITHMS2 : SIGN_ALGORITHMS);
        signature.initVerify(pubKey);
        signature.update(content.getBytes(StandardCharsets.UTF_8));
        return signature.verify(CodecUtil.convertFromBase64(sign));
    }

    /**
     * 加密方法
     *
     * @param source 源数据
     */
    @SneakyThrows
    public static String encrypt(@NonNull String source, @NonNull String publicKey) {
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PublicKey key = keyFactory.generatePublic(new X509EncodedKeySpec(CodecUtil.convertFromBase64(publicKey)));
        Cipher cipher = Cipher.getInstance(RSA_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] b = cipher.doFinal(source.getBytes(StandardCharsets.UTF_8));
        return CodecUtil.convertToBase64(b);
    }

    /**
     * 解密算法
     *
     * @param cryptoGraph 密文
     */
    @SneakyThrows
    public static String decrypt(@NonNull String cryptoGraph, @NonNull String privateKey) {
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey key = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(CodecUtil.convertFromBase64(privateKey)));
        Cipher cipher = Cipher.getInstance(RSA_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] b = CodecUtil.convertFromBase64(cryptoGraph);
        return new String(cipher.doFinal(b));
    }
}
