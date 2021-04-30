package org.rx.net.http.tunnel;

import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import lombok.RequiredArgsConstructor;
import org.rx.io.IOStream;
import org.rx.net.Sockets;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;

public class Server {
    @RequiredArgsConstructor
    static class SocksInfo {
        private final String clientSocksId;
        private final Channel channel;
        private final LinkedBlockingQueue<IOStream<?, ?>> sendQueue = new LinkedBlockingQueue<>();
    }

    class x {

    }

    public static final String GROUP_NAME = "TUNNEL";

    private final Map<String, Map<String, SocksInfo>> holds = new ConcurrentHashMap<>();

    private SocksInfo getSocksInfo(DataPack pack) {
        return holds.computeIfAbsent(pack.getAppName(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(pack.getSocksId(), k -> new SocksInfo(pack.getSocksId(), Sockets.bootstrap(GROUP_NAME, channel -> {
            channel.pipeline().addLast();
        }).connect(pack.getRemoteEndpoint()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                return;
            }
            holds.get
        }).channel()));
    }

    public void offer(DataPack pack) {
        SocksInfo socksInfo = ;
        if (!socksInfo.channel.isActive()) {
            socksInfo.sendQueue.offer(pack.getStream());
            return;
        }
//        PooledByteBufAllocator.DEFAULT.directBuffer();
        socksInfo.channel.writeAndFlush(pack.getStream());
    }

    public DataPack poll(DataPack id) {
        return null;
    }
}
