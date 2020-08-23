package org.rx.net.socks;

public interface Authenticator {
    boolean auth(String username, String password);
}
