package org.rx.net.shadowsocks.encryption;

import lombok.SneakyThrows;
import org.rx.core.Linq;

import java.lang.reflect.Constructor;

public class CryptoFactory {
    public static ICrypto get(String name, String password) {
        return get(name, password, false);
    }

    @SneakyThrows
    public static ICrypto get(String name, String password, boolean forUdp) {
        CipherKind cipherKind = Linq.from(CipherKind.values()).first(p -> p.getCipherName().equals(name));
        Constructor<?> constructor = cipherKind.type.getConstructor(String.class, String.class);
        ICrypto crypt = (ICrypto) constructor.newInstance(name, password);
        crypt.setForUdp(forUdp);
        return crypt;
    }
}
