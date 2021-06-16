package org.rx.net.shadowsocks.encryption;

import lombok.SneakyThrows;
import org.rx.core.exception.InvalidException;
import org.rx.net.shadowsocks.encryption.impl.*;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

public class CryptoFactory {
    private static final Map<String, String> cryptos = new HashMap<>();

    static {
        cryptos.putAll(AesCrypto.getCiphers());
        cryptos.putAll(BlowFishCrypto.getCiphers());
        cryptos.putAll(Rc4Md5Crypto.getCiphers());
        cryptos.putAll(Chacha20Crypto.getCiphers());
        cryptos.putAll(AesGcmCrypto.getCiphers());
    }

    public static ICrypto get(String name, String password) {
        return get(name, password, false);
    }

    @SneakyThrows
    public static ICrypto get(String name, String password, boolean forUdp) {
        String className = cryptos.get(name);
        if (className == null) {
            throw new InvalidException("ICrypto %s not found", name);
        }

        Class<?> clazz = Class.forName(className);
        Constructor<?> constructor = clazz.getConstructor(String.class, String.class);
        ICrypto crypt = (ICrypto) constructor.newInstance(name, password);
        crypt.setForUdp(forUdp);
        return crypt;
    }
}
