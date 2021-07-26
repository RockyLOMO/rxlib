package org.rx.net.socks;

public interface Authenticator {
    Authenticator NON_AUTH = (u, p) -> SocksUser.ANONYMOUS;

    static Authenticator dbAuth(Integer apiPort) {
        return new DbAuthenticator(apiPort);
    }

    SocksUser login(String username, String password);
}
