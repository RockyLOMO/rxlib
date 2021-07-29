package org.rx.net.socks;

import java.util.List;

public interface Authenticator {
    Authenticator NON_AUTH = (u, p) -> SocksUser.ANONYMOUS;

    static Authenticator dbAuth(List<SocksUser> initUsers, Integer apiPort) {
        return new DbAuthenticator(initUsers, apiPort);
    }

    SocksUser login(String username, String password);
}
