package org.rx.net.http;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.rx.bean.MultiValueMap;
import org.rx.core.Strings;
import org.rx.io.IOStream;

import java.util.Collections;
import java.util.Set;

import static org.rx.core.App.isNull;

@Getter
public class RequestBean {
    private final String uri;
    private final HttpMethod method;
    private final HttpHeaders headers = new DefaultHttpHeaders();
    @Getter(lazy = true)
    private final Set<Cookie> cookies = cookiesLazy();
    private final MultiValueMap<String, String> queryString = new MultiValueMap<>();

    @Getter(lazy = true)
    private final MultiValueMap<String, String> form = new MultiValueMap<>();
    @Getter(lazy = true)
    private final MultiValueMap<String, IOStream<?, ?>> files = new MultiValueMap<>();
    @Setter
    private ByteBuf content;

    private Set<Cookie> cookiesLazy() {
        String cookie = headers.get(HttpHeaderNames.COOKIE);
        if (Strings.isEmpty(cookie)) {
            return Collections.emptySet();
        }
        return ServerCookieDecoder.STRICT.decode(cookie);
    }

    public RequestBean(@NonNull String uri, HttpMethod method) {
        this.uri = uri;
        this.method = isNull(method, HttpMethod.GET);
    }
}
