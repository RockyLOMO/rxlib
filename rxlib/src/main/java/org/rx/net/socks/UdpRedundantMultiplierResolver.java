package org.rx.net.socks;

import java.net.InetSocketAddress;

/**
 * 根据 UDP 报文目的地址解析发送倍率。
 * <p>
 * 用于「分目的地 multiplier」：命中规则时覆盖全局静态倍率或自适应倍率。
 */
@FunctionalInterface
public interface UdpRedundantMultiplierResolver {

    /**
     * 表示未命中任何规则，应使用全局配置（{@code udpRedundantMultiplier} 或自适应 {@link UdpRedundantStats}）。
     */
    int NO_MATCH = -1;

    /**
     * @param destination 出站 {@link io.netty.channel.socket.DatagramPacket#recipient()}，可能为 {@code null}
     * @return 命中规则时返回 [1, 5]（含明确设为 1 以关闭冗余）；未命中返回 {@link #NO_MATCH}
     */
    int resolve(InetSocketAddress destination);
}
