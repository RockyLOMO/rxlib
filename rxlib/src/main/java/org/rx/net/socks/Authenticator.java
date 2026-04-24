package org.rx.net.socks;

public interface Authenticator {
    Authenticator NON_AUTH = (u, p) -> SocksUser.ANONYMOUS;

    SocksUser login(String username, String password);

    default AuthResult loginResult(String username, String password) {
        SocksUser user = login(username, password);
        return user == null ? null : new AuthResult(user, null);
    }
}
