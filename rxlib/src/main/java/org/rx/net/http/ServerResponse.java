package org.rx.net.http;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.rx.io.Bytes;

import static org.rx.core.Sys.toJsonString;

@RequiredArgsConstructor
@Getter
public class ServerResponse {
    public static final AsciiString APPLICATION_JSON = AsciiString.cached("application/json; charset=UTF-8");
    public static final AsciiString TEXT_HTML = AsciiString.cached("text/html; charset=UTF-8");
    private final HttpHeaders headers = new DefaultHttpHeaders();

    @Setter
    private HttpResponseStatus status = HttpResponseStatus.OK;

    public void addCookie(Cookie cookie) {
        headers.add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
    }

    public void setContentType(String contentType) {
        headers.set(HttpHeaderNames.CONTENT_TYPE, contentType);
    }

    private ByteBuf content;

    /**
     * Transfers ownership of one buffer reference to this response. The caller must not
     * release it after this call; replacing the content releases the previous reference.
     * Passing the currently owned instance does not retain or release it.
     */
    public void setContent(ByteBuf content) {
        if (this.content == content) {
            return;
        }
        releaseContent();
        this.content = content;
    }

    // Transfers ownership to the outbound response without changing the reference count.
    ByteBuf takeContent() {
        ByteBuf value = content;
        content = null;
        return value;
    }

    void releaseContent() {
        ByteBuf value = takeContent();
        if (value != null) {
            value.release();
        }
    }

    public void jsonBody(Object json) {
        writeBody(toJsonString(json), APPLICATION_JSON);
    }

    public void htmlBody(String html) {
        writeBody(html, TEXT_HTML);
    }

    private void writeBody(String text, AsciiString contentType) {
        ByteBuf buffer = Bytes.directBuffer();
        try {
            buffer.writeCharSequence(text, CharsetUtil.UTF_8);
            setContentType(contentType.toString());
            setContent(buffer);
            buffer = null;
        } finally {
            if (buffer != null) {
                buffer.release();
            }
        }
    }

    public void redirect(String newUri) {
        headers.set(HttpHeaderNames.LOCATION, newUri);
        status = HttpResponseStatus.FOUND;
        releaseContent();
    }
}
