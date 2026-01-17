package org.rx.net.socks.encryption;

import io.netty.buffer.ByteBuf;

public interface ICrypto {
    static ICrypto get(String name, String password) {
        return get(name, password, false);
    }

    static ICrypto get(String name, String password, boolean forUdp) {
        ICrypto crypt = CipherKind.newInstance(name, password);
        crypt.setForUdp(forUdp);
        return crypt;
    }

    void setForUdp(boolean forUdp);

    ByteBuf encrypt(ByteBuf in);

    ByteBuf decrypt(ByteBuf in);
}
