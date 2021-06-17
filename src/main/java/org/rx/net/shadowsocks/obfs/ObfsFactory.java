package org.rx.net.shadowsocks.obfs;

import io.netty.channel.ChannelHandler;
import org.rx.net.shadowsocks.obfs.impl.HttpSimpleHandler;

import java.util.Collections;
import java.util.List;

public class ObfsFactory {
    public static List<ChannelHandler> getObfsHandler(String obfs) {
        if (obfs == null) {
            return Collections.emptyList();
        }

        switch (obfs) {
            case HttpSimpleHandler.OBFS_NAME:
                return HttpSimpleHandler.getHandlers();
        }
        return Collections.emptyList();
    }
}
