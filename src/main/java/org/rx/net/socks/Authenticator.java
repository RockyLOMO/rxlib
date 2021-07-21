package org.rx.net.socks;

public interface Authenticator {
    Authenticator ANONYMOUS = (u, p) -> true;
    Authenticator DB_AUTH = new DbAuthenticator();

    boolean auth(String username, String password);
}
