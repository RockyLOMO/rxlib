//package org.rx.net.socks.upstream;
//
//import io.netty.channel.socket.SocketChannel;
//import lombok.NonNull;
//import org.rx.net.socks.SocksConfig;
//import org.rx.net.socks.SslUtil;
//
//import java.net.InetSocketAddress;
//import java.net.SocketAddress;
//
//public class SslDirectUpstream extends Upstream {
//    final SocksConfig config;
//
//    public SslDirectUpstream(@NonNull SocketAddress upstreamAddr, @NonNull SocksConfig config) {
//        address = upstreamAddr;
//        this.config = config;
//    }
//
//    @Override
//    public void initChannel(SocketChannel channel) {
//        SslUtil.addBackendHandler(channel, config.getTransportFlags(), (InetSocketAddress) getAddress(), true);
//    }
//}
