package org.rx.security;

import lombok.SneakyThrows;

import java.io.File;
import java.io.FileInputStream;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;

import static org.rx.Contract.require;

public class MD5Util {
    private static final char[] hexDigits = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e',
            'f' };

    @SneakyThrows
    public static byte[] md5(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            FileChannel ch = in.getChannel();
            md.update(ch.map(FileChannel.MapMode.READ_ONLY, 0, file.length()));
            return md.digest();
        }
    }

    public static byte[] md5(String data) {
        require(data);

        return md5(data.getBytes());
    }

    @SneakyThrows
    public static byte[] md5(byte[] data) {
        require(data);

        MessageDigest md = MessageDigest.getInstance("MD5");
        return md.digest(data);
    }

    public static String md5Hex(String data) {
        require(data);

        return md5Hex(data.getBytes());
    }

    public static String md5Hex(byte[] data) {
        require(data);

        return toHexString(md5(data));
    }

    /**
     * 方法md5HashCode32 中 ((int) md5Bytes[i]) & 0xff 操作的解释：
     * 在Java语言中涉及到字节byte数组数据的一些操作时，经常看到 byte[i] & 0xff这样的操作，这里就记录总结一下这里包含的意义：
     * 1、0xff是16进制（十进制是255），它默认为整形，二进制位为32位，最低八位是“1111 1111”，其余24位都是0。 2、&运算:
     * 如果2个bit都是1，则得1，否则得0； 3、byte[i] &
     * 0xff：首先，这个操作一般都是在将byte数据转成int或者其他整形数据的过程中；使用了这个操作，最终的整形数据只有低8位有数据，其他位数都为0。
     * 4、这个操作得出的整形数据都是大于等于0并且小于等于255的
     */
    private static String toHexString(byte[] b) {
        // 用字节表示就是 16 个字节
        char str[] = new char[16 * 2]; // 每个字节用 16 进制表示的话，使用两个字符，
        // 所以表示成 16 进制需要 32 个字符
        int k = 0; // 表示转换结果中对应的字符位置
        for (int i = 0; i < 16; i++) { // 从第一个字节开始，对 MD5 的每一个字节
            // 转换成 16 进制字符的转换
            byte byte0 = b[i]; // 取第 i 个字节
            str[k++] = hexDigits[byte0 >>> 4 & 0xf]; // 取字节中高 4 位的数字转换, >>>,
            // 逻辑右移，将符号位一起右移
            str[k++] = hexDigits[byte0 & 0xf]; // 取字节中低 4 位的数字转换
        }
        return new String(str); // 换后的结果转换为字符串
    }

    /**
     * Converts a hex string into a byte array.
     *
     * @param s - string to be converted
     * @return byte array converted from s
     */
    private static byte[] toByteArray(String s) {
        byte[] buf = new byte[s.length() / 2];
        int j = 0;
        for (int i = 0; i < buf.length; i++) {
            buf[i] = (byte) ((Character.digit(s.charAt(j++), 16) << 4) | Character.digit(s.charAt(j++), 16));
        }
        return buf;
    }
}
