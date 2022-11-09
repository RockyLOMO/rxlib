package org.rx.net.http;

import io.netty.handler.codec.http.HttpHeaderNames;
import lombok.Getter;
import lombok.Setter;
import okhttp3.Authenticator;
import okhttp3.Credentials;

import java.net.Proxy;
import java.net.SocketAddress;

@Getter
public class AuthenticProxy extends Proxy {
    private final Authenticator authenticator;
    @Setter
    private boolean directOnFail;

    public AuthenticProxy(Type type, SocketAddress sa, String username, String password) {
        super(type, sa);
        authenticator = (route, response) -> {
            String name = HttpHeaderNames.PROXY_AUTHORIZATION.toString();
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
