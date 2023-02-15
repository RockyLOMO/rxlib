package org.rx.net;

import lombok.*;
import org.apache.commons.collections4.MapUtils;
import org.rx.bean.Tuple;
import org.rx.core.StringBuilder;
import org.rx.core.Strings;
import org.rx.net.http.HttpClient;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@Getter
@Setter
public class AuthenticEndpoint implements Serializable {
    private static final long serialVersionUID = -8777400710799771803L;
    static final String AT_FLAG = "@", AUTH_FLAG = ":", PARAM_FLAG = "?";

    public static AuthenticEndpoint valueOf(String authenticEndpoint) {
        int i = Strings.lastIndexOf(authenticEndpoint, AT_FLAG);
        if (i == -1) {
            Tuple<InetSocketAddress, Map<String, String>> tuple = decodeEndpoint(authenticEndpoint);
            return new AuthenticEndpoint(tuple.left, null, null, tuple.right);
        }

        Tuple<InetSocketAddress, Map<String, String>> tuple = decodeEndpoint(authenticEndpoint.substring(i + AT_FLAG.length()));
        String auth = authenticEndpoint.substring(0, i);
        i = auth.indexOf(AUTH_FLAG);
        if (i == -1) {
            return new AuthenticEndpoint(tuple.left, auth, null, tuple.right);
        }
        return new AuthenticEndpoint(tuple.left, auth.substring(0, i), auth.substring(i + AUTH_FLAG.length()), tuple.right);
    }

    private static Tuple<InetSocketAddress, Map<String, String>> decodeEndpoint(String endpoint) {
        int i = endpoint.lastIndexOf(PARAM_FLAG);
        if (i == -1) {
            return Tuple.of(Sockets.parseEndpoint(endpoint), Collections.emptyMap());
        }
        return Tuple.of(Sockets.parseEndpoint(endpoint.substring(0, i)), HttpClient.decodeQueryString(endpoint.substring(i)));
    }

    private final InetSocketAddress endpoint;
    private String username, password;
    @Setter(AccessLevel.NONE)
    private Map<String, String> parameters;

    public Map<String, String> getParameters() {
        if (parameters == null) {
            parameters = new ConcurrentHashMap<>();
        }
        return parameters;
    }

    public AuthenticEndpoint(InetSocketAddress endpoint, String username, String password) {
        this(endpoint, username, password, null);
    }

    public AuthenticEndpoint(InetSocketAddress endpoint, String username, String password, Map<String, String> parameters) {
        this(endpoint);
        this.username = username;
        this.password = password;
        this.parameters = parameters;
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        if (!Strings.isEmpty(username)) {
            s.append(username);
        }
        if (!Strings.isEmpty(password)) {
            s.append(":%s", password);
        }
        s.append("@%s", Sockets.toString(endpoint));
        if (!MapUtils.isEmpty(parameters)) {
            s.append(HttpClient.buildUrl(null, (Map) parameters));
        }
        return s.toString();
    }
}
