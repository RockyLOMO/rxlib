package org.rx.net.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@RequiredArgsConstructor
@Getter
public class ResponseBean {
    private final HttpHeaders headers = new DefaultHttpHeaders();
    private final Set<Cookie> cookies = new HashSet<>();

    @Setter
    private String contentType = "text/plain; charset=UTF-8";
    @Setter
    private ByteBuf content = Unpooled.EMPTY_BUFFER;
}
