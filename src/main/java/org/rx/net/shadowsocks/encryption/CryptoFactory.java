package org.rx.net.shadowsocks.encryption;

import lombok.SneakyThrows;
import org.rx.core.NQuery;

import java.lang.reflect.Constructor;

public class CryptoFactory {
    public static ICrypto get(String name, String password) {
        return get(name, password, false);
    }

    @SneakyThrows
    public static ICrypto get(String name, String password, boolean forUdp) {
        CipherName cipherName = NQuery.of(CipherName.values()).first(p -> p.getName().equals(name));
        Constructor<?> constructor = cipherName.type.getConstructor(String.class, String.class);
        ICrypto crypt = (ICrypto) constructor.newInstance(name, password);
        crypt.setForUdp(forUdp);
        return crypt;
    }
}
