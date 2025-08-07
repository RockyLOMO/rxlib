package org.rx.codec;

import io.netty.buffer.ByteBufUtil;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.bouncycastle.util.encoders.HexTranslator;
import org.rx.io.Bytes;
import org.rx.io.FileStream;
import org.rx.io.MemoryStream;
import org.rx.io.Serializer;
import org.rx.third.open.CrcModel;

import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class CodecUtil {
    public static final HexTranslator HEX = new HexTranslator();
    static final FastThreadLocal<SecureRandom> secureRandom = new FastThreadLocal<>();

    public static SecureRandom threadLocalSecureRandom() {
        SecureRandom rnd = secureRandom.get();
        if (rnd == null) {
            secureRandom.set(rnd = new SecureRandom());
        }
        return rnd;
    }

    public static byte[] secureRandomBytes(int size) {
        byte[] bytes = new byte[size];
        CodecUtil.threadLocalSecureRandom().nextBytes(bytes);
        return bytes;
    }

    //org.apache.commons.codec.binary.Base64.isBase64(base64String) may not right
    public static String convertToBase64(byte[] data) {
        byte[] ret = Base64.getEncoder().encode(data);
        return new String(ret, StandardCharsets.UTF_8);
    }

    public static byte[] convertFromBase64(@NonNull String base64) {
        byte[] data = base64.getBytes(StandardCharsets.UTF_8);
        return Base64.getDecoder().decode(data);
    }

    public static <T> String serializeToBase64(T obj) {
        byte[] data = Serializer.DEFAULT.serializeToBytes(obj);
        return convertToBase64(data);
    }

    public static <T> T deserializeFromBase64(String base64) {
        byte[] data = convertFromBase64(base64);
        return Serializer.DEFAULT.deserialize(new MemoryStream(data, 0, data.length));
    }

    //guava hash
//    @SneakyThrows
//    public static long murmurHash3_64(BiAction<Hasher> fn) {
//        Hasher hasher = Hashing.murmur3_128().newHasher();
//        fn.invoke(hasher);
//        return hasher.hash().asLong();
//    }
//
//    //When using 128-bits, the x86 and x64 versions do not produce the same values
//    @SneakyThrows
//    public static ULID murmurHash3_128(BiAction<Hasher> fn) {
//        Hasher hasher = Hashing.murmur3_128().newHasher();
//        fn.invoke(hasher);
//        return ULID.valueOf(hasher.hash().asBytes());
//    }

    public static BigInteger hashUnsigned64(Object... args) {
        return hashUnsigned64(Serializer.DEFAULT.serializeToBytes(args));
    }

    public static BigInteger hashUnsigned64(byte[] buf) {
        return hashUnsigned64(buf, 0, buf.length);
    }

    //UnsignedLong.fromLongBits
    public static BigInteger hashUnsigned64(byte[] buf, int offset, int len) {
        long value = hash64(buf, offset, len);
        BigInteger bigInt = BigInteger.valueOf(value & 9223372036854775807L);
        if (value < 0L) {
            bigInt = bigInt.setBit(63);
        }
        return bigInt;
    }

    public static long hash64(Object... dataArray) {
        return hash64(Serializer.DEFAULT.serializeToBytes(dataArray));
    }

    public static long hash64(String data) {
        return hash64(data.getBytes(StandardCharsets.UTF_8));
    }

    public static long hash64(long data) {
        return hash64(Bytes.toBytes(data));
    }

    public static long hash64(byte[] buf) {
        return hash64(buf, 0, buf.length);
    }

    public static long hash64(byte[] buf, int offset, int len) {
        return CrcModel.CRC64_ECMA_182.getCRC(buf, offset, len).getCrc();
    }

    @SneakyThrows
    public static byte[] md5(@NonNull File file) {
        try (FileStream fs = new FileStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = Bytes.arrayBuffer();
            int read;
            while ((read = fs.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
            return md.digest();
        }
    }

    public static byte[] md5(@NonNull String data) {
        return md5(data.getBytes(StandardCharsets.UTF_8));
    }

    @SneakyThrows
    public static byte[] md5(byte[] data) {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return md.digest(data);
    }

    public static String hexMd5(@NonNull String data) {
        return hexMd5(data.getBytes(StandardCharsets.UTF_8));
    }

    public static String hexMd5(byte[] data) {
        return ByteBufUtil.hexDump(md5(data));
    }

//    /**
//     * 方法md5HashCode32 中 ((int) md5Bytes[i]) & 0xff 操作的解释：
//     * 在Java语言中涉及到字节byte数组数据的一些操作时，经常看到 byte[i] & 0xff这样的操作，这里就记录总结一下这里包含的意义：
//     * 1、0xff是16进制（十进制是255），它默认为整形，二进制位为32位，最低八位是“1111 1111”，其余24位都是0。 2、&运算:
//     * 如果2个bit都是1，则得1，否则得0； 3、byte[i] &
//     * 0xff：首先，这个操作一般都是在将byte数据转成int或者其他整形数据的过程中；使用了这个操作，最终的整形数据只有低8位有数据，其他位数都为0。
//     * 4、这个操作得出的整形数据都是大于等于0并且小于等于255的
//     */
//    public static String toHexString(byte[] data) {
//        // 用字节表示就是 16 个字节
//        char[] str = new char[16 * 2]; // 每个字节用 16 进制表示的话，使用两个字符，
//        // 所以表示成 16 进制需要 32 个字符
//        int k = 0; // 表示转换结果中对应的字符位置
//        for (int i = 0; i < 16; i++) { // 从第一个字节开始，对 MD5 的每一个字节
//            // 转换成 16 进制字符的转换
//            byte byte0 = data[i]; // 取第 i 个字节
//            str[k++] = hexDigits[byte0 >>> 4 & 0xf]; // 取字节中高 4 位的数字转换, >>>,
//            // 逻辑右移，将符号位一起右移
//            str[k++] = hexDigits[byte0 & 0xf]; // 取字节中低 4 位的数字转换
//        }
//        return new String(str); // 换后的结果转换为字符串
//    }
}
