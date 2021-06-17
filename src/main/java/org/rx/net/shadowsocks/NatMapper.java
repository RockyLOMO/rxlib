package org.rx.net.shadowsocks;

import io.netty.channel.Channel;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NatMapper {
    private static final Map<InetSocketAddress, Channel> udpTable = new ConcurrentHashMap<>();

    static void putUdpChannel(InetSocketAddress udpTarget, Channel udpChannel) {
        udpTable.put(udpTarget, udpChannel);
    }

    static Channel getUdpChannel(InetSocketAddress udpTarget) {
        return udpTable.get(udpTarget);
    }

    static void closeUdpChannel(InetSocketAddress udpTarget) {
        Channel udpChannel = udpTable.remove(udpTarget);
        if (udpChannel != null && udpChannel.isActive()) {
            udpChannel.close();
        }
    }
}
