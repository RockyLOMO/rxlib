package org.rx.net.shadowsocks.ss.obfs;

import io.netty.channel.ChannelHandler;
import org.rx.net.shadowsocks.ss.obfs.impl.HttpSimpleHandler;

import java.util.Collections;
import java.util.List;

public class ObfsFactory {
    public static List<ChannelHandler> getObfsHandler(String obfs) {
        switch (obfs) {
            case HttpSimpleHandler.OBFS_NAME:
                return HttpSimpleHandler.getHandlers();
        }
        return Collections.emptyList();
    }
}
