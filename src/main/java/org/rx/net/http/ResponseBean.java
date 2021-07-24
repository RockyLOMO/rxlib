package org.rx.net.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.rx.io.Bytes;

import static org.rx.core.App.toJsonString;

@RequiredArgsConstructor
@Getter
public class ResponseBean {
    public static final AsciiString APPLICATION_JSON = AsciiString.cached("application/json; charset=UTF-8");
    public static final AsciiString TEXT_HTML = AsciiString.cached("text/html; charset=UTF-8");
    private final HttpHeaders headers = new DefaultHttpHeaders();

    public void addCookie(Cookie cookie) {
        headers.add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
    }

    public void setContentType(String contentType) {
        headers.set(HttpHeaderNames.CONTENT_TYPE, contentType);
    }

    @Setter
    private ByteBuf content;

    public void jsonBody(Object json) {
        setContentType(APPLICATION_JSON.toString());
        content = Bytes.directBuffer();
        content.writeCharSequence(toJsonString(json), CharsetUtil.UTF_8);
    }

    public void htmlBody(String html) {
        setContentType(TEXT_HTML.toString());
        content = Bytes.directBuffer();
        content.writeCharSequence(html, CharsetUtil.UTF_8);
    }
}
