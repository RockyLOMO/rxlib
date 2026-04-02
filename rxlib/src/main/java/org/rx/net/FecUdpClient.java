package org.rx.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.rx.util.function.TripleAction;

import java.net.InetSocketAddress;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于 FEC 的 UDP 客户端
 * <p>
 * 用于游戏加速场景，通过前向纠错减少丢包导致的延迟。
 * 发送端自动将每 K 个数据包编码为一组并附加奇偶校验包，
 * 接收端自动检测丢包并通过 XOR 恢复。
 * <p>
 * 使用示例:
 * 
 * <pre>{@code
 * FecConfig config = new FecConfig();
 * config.setGroupSize(3);
 * FecUdpClient client = new FecUdpClient(0, config);
 * client.onReceive((sender, packet) -> {
 *     // 处理收到的数据
 * });
 * client.send(remoteAddr, data);
 * }</pre>
 */
@Slf4j
public class FecUdpClient implements AutoCloseable {
    private final CopyOnWriteArrayList<TripleAction<FecUdpClient, DatagramPacket>> receiveListeners = new CopyOnWriteArrayList<>();

    @Getter
    private final FecConfig config;
    private final Bootstrap bootstrap;
    @Getter
    private final Channel channel;

    /**
     * @param bindPort 本地绑定端口，0 表示随机
     * @param config FEC 配置
     */
    public FecUdpClient(int bindPort, FecConfig config) {
        this.config = config;
        FecUdpClient self = this;

        this.bootstrap = Sockets.udpBootstrap(null, (DatagramChannel ch) -> {
            ChannelPipeline pipeline = ch.pipeline();
            // Inbound: FecDecoder → UserHandler
            pipeline.addLast("fecDecoder", new FecDecoder(config));
            pipeline.addLast("fecHandler", new SimpleChannelInboundHandler<DatagramPacket>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                    self.fireReceive(msg.retain());
                }
            });
            // Outbound: FecEncoder
            pipeline.addLast("fecEncoder", new FecEncoder(config));
        });
        this.channel = bootstrap.bind(bindPort).syncUninterruptibly().channel();
        log.info("FecUdpClient started on {}", channel.localAddress());
    }

    /**
     * 注册接收回调
     */
    public void onReceive(TripleAction<FecUdpClient, DatagramPacket> listener) {
        receiveListeners.add(listener);
    }

    /**
     * 发送数据到远端地址
     *
     * @param remote 远端地址
     * @param data 数据 (ByteBuf，ownership 转移给 FEC encoder)
     * @return ChannelFuture
     */
    public ChannelFuture send(InetSocketAddress remote, ByteBuf data) {
        return channel.writeAndFlush(new DatagramPacket(data, remote));
    }

    /**
     * 获取本地绑定地址
     */
    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) channel.localAddress();
    }

    private void fireReceive(DatagramPacket packet) {
        try {
            for (TripleAction<FecUdpClient, DatagramPacket> listener : receiveListeners) {
                listener.accept(this, packet);
            }
        } catch (Exception e) {
            log.error("FecUdpClient onReceive error", e);
        } finally {
            packet.release();
        }
    }

    @Override
    public void close() {
        channel.close().syncUninterruptibly();
        log.info("FecUdpClient closed");
    }
}
