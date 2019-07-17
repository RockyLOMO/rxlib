package org.rx.socks.http;

import lombok.Getter;
import lombok.Setter;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
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
        authenticator = new Authenticator() {
            @Nullable
            @Override
            public Request authenticate(@Nullable Route route, @NotNull Response response) throws IOException {
                String name = "Proxy-Authorization";
                if (directOnFail && response.request().header(name) != null) {
                    return null;
                }
                String credential = Credentials.basic(username, password);
                return response.request().newBuilder()
                        .header(name, credential)
                        .build();
            }
        };
        directOnFail = true;
    }
}
