package org.rx.net.shadowsocks.encryption;

import io.netty.buffer.ByteBuf;
import org.rx.io.Bytes;

public class CryptoUtil {
    public static byte[] encrypt(ICrypto crypt, ByteBuf msg) {
        byte[] arr = new byte[msg.readableBytes()];
        msg.getBytes(0, arr);
        crypt.encrypt(arr, msg);
        return Bytes.getBytes(msg);
    }

    public static byte[] decrypt(ICrypto crypt, ByteBuf msg) {
        byte[] arr = new byte[msg.readableBytes()];
        msg.getBytes(0, arr);
        crypt.decrypt(arr, msg);
        return Bytes.getBytes(msg);
    }
}
