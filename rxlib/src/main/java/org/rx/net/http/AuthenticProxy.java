package org.rx.net.http;

import lombok.Getter;
import lombok.Setter;

import java.net.Proxy;
import java.net.SocketAddress;

@Getter
public class AuthenticProxy extends Proxy {
    private final String username;
    private final String password;
    @Setter
    private boolean directOnFail;

    public AuthenticProxy(Type type, SocketAddress sa) {
        this(type, sa, null, null);
    }

    public AuthenticProxy(Type type, SocketAddress sa, String username, String password) {
        super(type, sa);
        this.username = username;
        this.password = password;
        directOnFail = true;
    }
}
