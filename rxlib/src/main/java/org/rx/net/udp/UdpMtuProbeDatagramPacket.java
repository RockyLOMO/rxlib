package org.rx.net.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;

/**
 * UDP MTU 探测包允许超过当前本地 MTU guard，真正由路径是否回 ACK 来收敛。
 */
public final class UdpMtuProbeDatagramPacket extends DatagramPacket {
    public UdpMtuProbeDatagramPacket(ByteBuf data, InetSocketAddress recipient) {
        super(data, recipient);
    }
}
