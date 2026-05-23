package org.rx.net.udp;

import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;

import java.io.Serializable;
import java.net.InetSocketAddress;

/**
 * 解析 UDP protect 的 flow/session 标识，用于隔离同一 channel 上的多 peer。
 */
public interface UdpProtectFlowIdResolver extends Serializable {
    UdpProtectFlowIdResolver RECIPIENT = new UdpProtectFlowIdResolver() {
        private static final long serialVersionUID = 1L;

        @Override
        public int resolve(Channel channel, DatagramPacket packet) {
            InetSocketAddress address = packet.recipient() != null ? packet.recipient() : packet.sender();
            return UdpProtectAttributes.addressHash(UdpProtectAttributes.normalize(address));
        }
    };

    int resolve(Channel channel, DatagramPacket packet);
}
