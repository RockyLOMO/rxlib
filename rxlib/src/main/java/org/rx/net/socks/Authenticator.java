package org.rx.net.socks;

public interface Authenticator {
    Authenticator NON_AUTH = (u, p) -> SocksUser.ANONYMOUS;

    SocksUser login(String username, String password);
}
