package org.rx.net;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.rx.core.StringBuilder;
import org.rx.core.Strings;

import java.io.Serializable;
import java.net.InetSocketAddress;

@Getter
@EqualsAndHashCode
public class AuthenticEndpoint implements Serializable {
    private static final long serialVersionUID = -8777400710799771803L;
    private final InetSocketAddress endpoint;
    private String username, password;

    public AuthenticEndpoint(String authenticEndpoint) {
        String flag = "@";
        if (!Strings.contains(authenticEndpoint, flag)) {
            endpoint = Sockets.parseEndpoint(authenticEndpoint);
            return;
        }

        String[] pair = Strings.split(authenticEndpoint, flag, 2);
        endpoint = Sockets.parseEndpoint(pair[1]);
        flag = ":";
        if (!Strings.contains(pair[0], flag)) {
            username = pair[0];
            return;
        }
        pair = Strings.split(pair[0], flag, 2);
        username = pair[0];
        password = pair[1];
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
