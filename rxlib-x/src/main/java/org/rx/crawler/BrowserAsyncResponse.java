package org.rx.crawler;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.net.InetSocketAddress;

@Getter
@RequiredArgsConstructor
public class BrowserAsyncResponse implements Serializable {
    private final BrowserAsyncRequest request;
    private final InetSocketAddress endpoint;
}
