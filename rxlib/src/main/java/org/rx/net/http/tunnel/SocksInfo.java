package org.rx.net.http.tunnel;

import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SocksInfo {
    private final String appName;
    private final Channel channel;

    public String getId() {
        return channel.id().asLongText();
    }
}
