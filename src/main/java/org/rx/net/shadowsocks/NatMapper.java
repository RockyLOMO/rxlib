package org.rx.net.shadowsocks;

import io.netty.channel.Channel;
import org.rx.util.function.BiFunc;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class NatMapper {
    private static final Map<InetSocketAddress, Channel> udpTable = new ConcurrentHashMap<>();

    static Channel getChannel(InetSocketAddress udpTarget, BiFunc<InetSocketAddress, Channel> loadFn) {
        return udpTable.computeIfAbsent(udpTarget, loadFn.toFunction());
    }

    static void closeChannel(InetSocketAddress udpTarget) {
        Channel udpChannel = udpTable.remove(udpTarget);
        if (udpChannel != null && udpChannel.isActive()) {
            udpChannel.close();
        }
    }
}
