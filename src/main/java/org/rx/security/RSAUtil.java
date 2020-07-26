package org.rx.security;

import lombok.SneakyThrows;
import org.rx.core.App;
import org.rx.core.Contract;

import javax.crypto.Cipher;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.rx.core.Contract.require;

public final class RSAUtil {
    private static final String SIGN_ALGORITHMS = "MD5withRSA";
    private static final String SIGN_ALGORITHMS2 = "SHA1WithRSA";
    private static final String RSA_ALGORITHM = "RSA/ECB/PKCS1Padding";

    @SneakyThrows
    public static String[] generateKeyPair() {
        KeyPairGenerator keygen = KeyPairGenerator.getInstance("RSA");
        keygen.initialize(1024, new SecureRandom());
        KeyPair keys = keygen.genKeyPair();
        PublicKey pubkey = keys.getPublic();
        PrivateKey prikey = keys.getPrivate();

        String pubKeyStr = App.convertToBase64String(pubkey.getEncoded());
        String priKeyStr = App.convertToBase64String(prikey.getEncoded());
        return new String[]{pubKeyStr, priKeyStr};
    }

    public static String sign(TreeMap<String, Object> map, String privateKey) {
        require(map, privateKey);

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
    public static String sign(String content, String privateKey, boolean isSHA1) {
        require(content, privateKey);

        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(App.convertFromBase64String(privateKey));
        KeyFactory keyf = KeyFactory.getInstance("RSA");
        PrivateKey priKey = keyf.generatePrivate(keySpec);

        Signature signature = Signature.getInstance(isSHA1 ? SIGN_ALGORITHMS2 : SIGN_ALGORITHMS);
        signature.initSign(priKey);
        signature.update(getContentBytes(content, Contract.UTF_8));
        return App.convertToBase64String(signature.sign());
    }

    public static boolean verify(TreeMap<String, Object> map, String sign, String publicKey) {
        require(map, sign, publicKey);

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
    public static boolean verify(String content, String sign, String publicKey, boolean isSHA1) {
        require(content, sign, publicKey);

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PublicKey pubKey = keyFactory.generatePublic(new X509EncodedKeySpec(App.convertFromBase64String(publicKey)));

        Signature signature = Signature.getInstance(isSHA1 ? SIGN_ALGORITHMS2 : SIGN_ALGORITHMS);
        signature.initVerify(pubKey);
        signature.update(getContentBytes(content, Contract.UTF_8));
        return signature.verify(App.convertFromBase64String(sign));
    }

    /**
     * 使用给定的 charset 将此 String 编码到 byte 序列，并将结果存储到新的 byte 数组。
     *
     * @param content 字符串对象
     * @param charset 编码方式
     * @return 所得 byte 数组
     */
    @SneakyThrows
    private static byte[] getContentBytes(String content, String charset) {
        if (charset == null || "".equals(charset)) {
            return content.getBytes();
        }

        return content.getBytes(charset);
    }

    /**
     * 加密方法
     *
     * @param source 源数据
     */
    @SneakyThrows
    public static String encrypt(String source, String publicKey) {
        require(source, publicKey);

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PublicKey key = keyFactory.generatePublic(new X509EncodedKeySpec(App.convertFromBase64String(publicKey)));
        Cipher cipher = Cipher.getInstance(RSA_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] b = cipher.doFinal(source.getBytes());
        return App.convertToBase64String(b);
    }

    /**
     * 解密算法
     *
     * @param cryptograph 密文
     */
    @SneakyThrows
    public static String decrypt(String cryptograph, String privateKey) {
        require(cryptograph, privateKey);

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey key = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(App.convertFromBase64String(privateKey)));
        Cipher cipher = Cipher.getInstance(RSA_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] b = App.convertFromBase64String(cryptograph);
        return new String(cipher.doFinal(b));
    }

    public static void main(String[] args) {
        UUID id = UUID.randomUUID();
        String[] kp = RSAUtil.generateKeyPair();
        System.out.println("id=" + id + ", kp=" + Contract.toJsonString(kp));

        String publicKey = kp[0];
        String privateKey = kp[1];
        String content = "这是一个使用RSA公私钥对加解密的例子";

        String signMsg = sign(content, privateKey);
        System.out.println("sign: " + signMsg);
        boolean verifySignResult = verify(content, signMsg, publicKey);
        System.out.println("verify: " + verifySignResult);

        signMsg = encrypt(content, publicKey);
        System.out.println("encrypt: " + signMsg);
        System.out.println("decrypt: " + decrypt(signMsg, privateKey));
    }
}
