package org.rx.net.shadowsocks.ss.obfs;

import io.netty.channel.ChannelHandler;
import org.rx.net.shadowsocks.ss.obfs.impl.HttpSimpleHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ObfsFactory {

    private static Logger logger = LoggerFactory.getLogger(ObfsFactory.class);

    public static List<ChannelHandler> getObfsHandler(String obfs) {
        switch (obfs) {
            case HttpSimpleHandler.OBFS_NAME:
                return HttpSimpleHandler.getHandlers();
        }
        return null;
    }
}
