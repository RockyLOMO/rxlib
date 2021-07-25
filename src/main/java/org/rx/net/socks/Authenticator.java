package org.rx.net.socks;

public interface Authenticator {
    Authenticator NON_AUTH = (u, p) -> SocksUser.ANONYMOUS;

    static Authenticator createDbAuth(Integer apiPort) {
        return new DbAuthenticator(apiPort);
    }

    SocksUser login(String username, String password);
}
