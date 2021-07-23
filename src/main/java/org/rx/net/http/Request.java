package org.rx.net.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cookie.Cookie;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class Request {
   String uri;
   HttpMethod method;
   HttpHeaders headers;
    Set<Cookie> cookies;
    Map<String, List<String>> queryString;
}
