package org.rx.net.shadowsocks.encryption;

import io.netty.buffer.ByteBuf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class CryptoUtil {
    public static byte[] encrypt(ICrypto crypt, Object msg) {
        byte[] data = null;
        ByteArrayOutputStream _remoteOutStream = null;
        try {
            _remoteOutStream = new ByteArrayOutputStream(64 * 1024);
            ByteBuf bytebuff = (ByteBuf) msg;
            int len = bytebuff.readableBytes();
            byte[] arr = new byte[len];
            bytebuff.getBytes(0, arr);
            crypt.encrypt(arr, _remoteOutStream);
            data = _remoteOutStream.toByteArray();
        } finally {
            if (_remoteOutStream != null) {
                try {
                    _remoteOutStream.close();
                } catch (IOException e) {
                }
            }
        }
        return data;
    }

    public static byte[] decrypt(ICrypto crypt, Object msg) {
        byte[] data = null;
        ByteArrayOutputStream _localOutStream = null;
        try {
            _localOutStream = new ByteArrayOutputStream(64 * 1024);
            ByteBuf bytebuff = (ByteBuf) msg;
            int len = bytebuff.readableBytes();
            byte[] arr = new byte[len];
            bytebuff.getBytes(0, arr);
            crypt.decrypt(arr, _localOutStream);
            data = _localOutStream.toByteArray();
        } finally {
            if (_localOutStream != null) {
                try {
                    _localOutStream.close();
                } catch (IOException e) {
                }
            }
        }
        return data;
    }
}
