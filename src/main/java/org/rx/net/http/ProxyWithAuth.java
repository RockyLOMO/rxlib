package org.rx.net.http;

import lombok.Getter;
import lombok.Setter;
import okhttp3.*;

import java.net.Proxy;
import java.net.SocketAddress;

public class ProxyWithAuth extends Proxy {
    @Getter
    private Authenticator authenticator;
    @Getter
    @Setter
    private boolean directOnFail;

    public ProxyWithAuth(Type type, SocketAddress sa, String username, String password) {
        super(type, sa);
        authenticator = (route, response) -> {
            String name = "Proxy-Authorization";
            if (directOnFail && response.request().header(name) != null) {
                return null;
            }
            String credential = Credentials.basic(username, password);
            return response.request().newBuilder()
                    .header(name, credential)
                    .build();
        };
        directOnFail = true;
    }
}
