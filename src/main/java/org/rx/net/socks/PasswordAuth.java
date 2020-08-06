package org.rx.net.socks;

public interface PasswordAuth {
    boolean auth(String user, String password);
}
