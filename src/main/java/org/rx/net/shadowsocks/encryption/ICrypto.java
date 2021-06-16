package org.rx.net.shadowsocks.encryption;

import java.io.ByteArrayOutputStream;

public interface ICrypto {
    void setForUdp(boolean forUdp);

    void encrypt(byte[] data, ByteArrayOutputStream stream);

    void encrypt(byte[] data, int length, ByteArrayOutputStream stream);

    void decrypt(byte[] data, ByteArrayOutputStream stream);

    void decrypt(byte[] data, int length, ByteArrayOutputStream stream);
}
