package org.rx.security;

import io.netty.buffer.ByteBufUtil;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.io.Bytes;
import org.rx.io.FileStream;

import java.io.File;
import java.security.MessageDigest;

public class MD5Util {
    @SneakyThrows
    public static byte[] md5(File file) {
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
        return md5(data.getBytes());
    }

    @SneakyThrows
    public static byte[] md5(@NonNull byte[] data) {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return md.digest(data);
    }

    public static String md5Hex(@NonNull String data) {
        return md5Hex(data.getBytes());
    }

    public static String md5Hex(@NonNull byte[] data) {
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
