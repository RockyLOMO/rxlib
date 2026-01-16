package org.rx.net.shadowsocks.encryption;

import io.netty.buffer.ByteBuf;
import lombok.SneakyThrows;
import org.rx.core.Linq;

import java.lang.reflect.Constructor;

public interface ICrypto {
    static ICrypto get(String name, String password) {
        return get(name, password, false);
    }

    @SneakyThrows
    static ICrypto get(String name, String password, boolean forUdp) {
        CipherKind cipherKind = Linq.from(CipherKind.values()).first(p -> p.getCipherName().equals(name));
        Constructor<?> constructor = cipherKind.type.getConstructor(String.class, String.class);
        ICrypto crypt = (ICrypto) constructor.newInstance(name, password);
        crypt.setForUdp(forUdp);
        return crypt;
    }

    void setForUdp(boolean forUdp);

    ByteBuf encrypt(ByteBuf in);

    ByteBuf decrypt(ByteBuf in);
}
