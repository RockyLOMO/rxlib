package org.rx.net.shadowsocks.encryption;

import io.netty.buffer.ByteBuf;

public interface ICrypto {
    void setForUdp(boolean forUdp);

    void encrypt(byte[] data, ByteBuf stream);

    void encrypt(byte[] data, int length, ByteBuf stream);

    void decrypt(byte[] data, ByteBuf stream);

    void decrypt(byte[] data, int length, ByteBuf stream);
}
