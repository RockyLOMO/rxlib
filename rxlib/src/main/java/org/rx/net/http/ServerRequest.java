package org.rx.net.http;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.util.CharsetUtil;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.rx.bean.MultiValueMap;
import org.rx.core.Strings;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Set;

import static org.rx.core.Extends.ifNull;
import static org.rx.core.Extends.require;

@Getter
public final class ServerRequest {
    private final InetSocketAddress remoteEndpoint;
    private final String uri;
    private final HttpMethod method;
    private final HttpHeaders headers = new DefaultHttpHeaders();
    @Getter(lazy = true)
    private final Set<Cookie> cookies = cookiesLazy();
    private final MultiValueMap<String, String> queryString = new MultiValueMap<>();

    @Setter(AccessLevel.PROTECTED)
    private MultiValueMap<String, String> form;
    @Setter(AccessLevel.PROTECTED)
    private MultiValueMap<String, FileUpload> files;
    @Setter(AccessLevel.PROTECTED)
    private ByteBuf content;
    @Getter(AccessLevel.NONE)
    String jsonBody;

    private Set<Cookie> cookiesLazy() {
        String cookie = headers.get(HttpHeaderNames.COOKIE);
        if (Strings.isEmpty(cookie)) {
            return Collections.emptySet();
        }
        return ServerCookieDecoder.STRICT.decode(cookie);
    }

    public String getContentType() {
        return headers.get(HttpHeaderNames.CONTENT_TYPE);
    }

    public ServerRequest(@NonNull InetSocketAddress remoteEndpoint, @NonNull String uri, HttpMethod method) {
        this.remoteEndpoint = remoteEndpoint;
        this.uri = uri;
        this.method = ifNull(method, HttpMethod.GET);
    }

    public String jsonBody() {
        if (jsonBody == null) {
            require(content);
            jsonBody = content.toString(CharsetUtil.UTF_8);
        }
        return jsonBody;
    }
}
