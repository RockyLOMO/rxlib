package org.rx.net.socks;

public interface Authenticator {
    Authenticator NON_AUTH = (u, p) -> SocksUser.ANONYMOUS;

    SocksUser login(String username, String password);

    default AuthResult loginResult(String username, String password) {
        SocksUser user = login(username, password);
        return user == null ? null : new AuthResult(user, null);
    }

    /** Optional secret lookup for Transport Core v2 HMAC authentication (null if user unknown or unsupported). */
    default String lookupSecret(String username) {
        return null;
    }
}
