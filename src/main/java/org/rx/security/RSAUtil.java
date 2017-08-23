package org.rx.security;

import com.alibaba.fastjson.JSONArray;
import org.rx.util.App;

import javax.crypto.Cipher;
import java.io.UnsupportedEncodingException;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.rx.common.Contract.require;

public class RSAUtil extends App {
    private static final String SIGN_ALGORITHMS  = "MD5withRSA";
    private static final String SIGN_ALGORITHMS2 = "SHA1WithRSA";
    private static final String RSA_ALGORITHM    = "RSA/ECB/PKCS1Padding";

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
     * @param content 需要加签名的数据
     * @param privateKey {@code RSA}的私钥
     * @param isSHA1 数据的编码方式
     * @return 返回签名信息
     */
    public static String sign(String content, String privateKey, boolean isSHA1) {
        require(content, privateKey);

        try {
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(convertFromBase64String(privateKey));
            KeyFactory keyf = KeyFactory.getInstance("RSA");
            PrivateKey priKey = keyf.generatePrivate(keySpec);

            Signature signature = Signature.getInstance(isSHA1 ? SIGN_ALGORITHMS2 : SIGN_ALGORITHMS);
            signature.initSign(priKey);
            signature.update(getContentBytes(content, UTF8));
            return convertToBase64String(signature.sign());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
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
     * @param content 需要加签名的数据
     * @param sign 签名信息
     * @param publicKey {@code RSA}的公钥
     * @param isSHA1 数据的编码方式
     * @return 是否验证通过。{@code True}表示通过
     */
    public static boolean verify(String content, String sign, String publicKey, boolean isSHA1) {
        require(content, sign, publicKey);

        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey pubKey = keyFactory.generatePublic(new X509EncodedKeySpec(convertFromBase64String(publicKey)));

            Signature signature = Signature.getInstance(isSHA1 ? SIGN_ALGORITHMS2 : SIGN_ALGORITHMS);
            signature.initVerify(pubKey);
            signature.update(getContentBytes(content, UTF8));
            return signature.verify(convertFromBase64String(sign));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * 使用给定的 charset 将此 String 编码到 byte 序列，并将结果存储到新的 byte 数组。
     *
     * @param content 字符串对象
     * @param charset 编码方式
     * @return 所得 byte 数组
     */
    private static byte[] getContentBytes(String content, String charset) {
        if (charset == null || "".equals(charset)) {
            return content.getBytes();
        }

        try {
            return content.getBytes(charset);
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalArgumentException("Not support:" + charset, ex);
        }
    }

    /**
     * 加密方法 source： 源数据
     */
    public static String encrypt(String source, String publicKey) {
        require(source, publicKey);

        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey key = keyFactory.generatePublic(new X509EncodedKeySpec(convertFromBase64String(publicKey)));
            /** 得到Cipher对象来实现对源数据的RSA加密 */
            Cipher cipher = Cipher.getInstance(RSA_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] b = cipher.doFinal(source.getBytes());
            /** 执行加密操作 */
            return convertToBase64String(b);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * 解密算法 cryptograph:密文
     */
    public static String decrypt(String cryptograph, String privateKey) {
        require(cryptograph, privateKey);

        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey key = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(convertFromBase64String(privateKey)));
            /** 得到Cipher对象对已用公钥加密的数据进行RSA解密 */
            Cipher cipher = Cipher.getInstance(RSA_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] b = convertFromBase64String(cryptograph);
            /** 执行解密操作 */
            return new String(cipher.doFinal(b));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * RSA生成签名与验证签名示例
     *
     * @param args
     */
    public static void main(String[] args) {
        UUID id = UUID.randomUUID();
        String[] kp = RSAUtil.generateKeyPair();
        System.out.println("id=" + id + ", kp=" + JSONArray.toJSONString(kp));

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

    private static String[] generateKeyPair() {
        KeyPairGenerator keygen;
        try {
            keygen = KeyPairGenerator.getInstance("RSA");
        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException(ex);
        }
        keygen.initialize(1024, new SecureRandom());
        KeyPair keys = keygen.genKeyPair();
        PublicKey pubkey = keys.getPublic();
        PrivateKey prikey = keys.getPrivate();

        String pubKeyStr = convertToBase64String(pubkey.getEncoded());
        String priKeyStr = convertToBase64String(prikey.getEncoded());
        return new String[] { pubKeyStr, priKeyStr };
    }
}
