package org.rx.net.socks;

public interface Authenticator {
    Authenticator ANONYMOUS = (u, p) -> true;

    boolean auth(String username, String password);
}
