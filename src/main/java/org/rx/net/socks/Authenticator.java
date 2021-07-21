package org.rx.net.socks;

public interface Authenticator {
    Authenticator NON_AUTH = (u, p) -> SocksUser.ANONYMOUS;
    Authenticator DB_AUTH = new DbAuthenticator();

    SocksUser login(String username, String password);
}
