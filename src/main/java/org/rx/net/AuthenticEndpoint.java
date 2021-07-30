package org.rx.net;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.rx.core.StringBuilder;
import org.rx.core.Strings;

import java.io.Serializable;
import java.net.InetSocketAddress;

@RequiredArgsConstructor
@Data
public class AuthenticEndpoint implements Serializable {
    private static final long serialVersionUID = -8777400710799771803L;

    public static AuthenticEndpoint valueOf(String authenticEndpoint) {
        InetSocketAddress endpoint;
        String flag = "@";
        if (!Strings.contains(authenticEndpoint, flag)) {
            endpoint = Sockets.parseEndpoint(authenticEndpoint);
            return new AuthenticEndpoint(endpoint);
        }

        String[] pair = Strings.split(authenticEndpoint, flag, 2);
        endpoint = Sockets.parseEndpoint(pair[1]);
        flag = ":";
        if (!Strings.contains(pair[0], flag)) {
            return new AuthenticEndpoint(endpoint, pair[0], null);
        }
        pair = Strings.split(pair[0], flag, 2);
        return new AuthenticEndpoint(endpoint, pair[0], pair[1]);
    }

    private final InetSocketAddress endpoint;
    private String username, password;

    public AuthenticEndpoint(InetSocketAddress endpoint, String username, String password) {
        this(endpoint);
        this.username = username;
        this.password = password;
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        if (!Strings.isNullOrEmpty(username)) {
            s.append(username);
        }
        if (!Strings.isNullOrEmpty(password)) {
            s.append(":%s", password);
        }
        s.append("@%s", Sockets.toString(endpoint));
        return s.toString();
    }
}
