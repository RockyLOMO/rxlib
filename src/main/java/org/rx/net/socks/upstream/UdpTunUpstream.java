//package org.rx.net.socks.upstream;
//
//import io.netty.channel.Channel;
//import lombok.NonNull;
//import org.rx.bean.RandomList;
//import org.rx.core.ShellExecutor;
//import org.rx.net.AuthenticEndpoint;
//import org.rx.net.socks.SocksContext;
//import org.rx.net.socks.UdpManager;
//import org.rx.net.support.UnresolvedEndpoint;
//import org.rx.net.support.UpstreamSupport;
//
//import java.net.InetSocketAddress;
//
//public class UdpTunUpstream extends UdpUpstream {
//    final RandomList<UpstreamSupport> servers;
//
//    public UdpTunUpstream(@NonNull UnresolvedEndpoint dstEp, @NonNull RandomList<UpstreamSupport> servers) {
//        super(dstEp);
//        this.servers = servers;
//    }
//
//    @Override
//    public void initChannel(Channel channel) {
//        UpstreamSupport next = servers.next();
//        AuthenticEndpoint svrEp = next.getEndpoint();
//
//        ShellExecutor udp2raw = new ShellExecutor(String.format("udp2raw_mp.exe -c -l0.0.0.0:%s -r%s -k \"%s\" --raw-mode faketcp --cipher-mode none --auth-mode simple",
//                ((InetSocketAddress) channel.localAddress()).getPort(),
//                svrEp.getEndpoint(), svrEp.getUsername()))
//                .start();
//        UdpManager.tun(SocksContext.attr(channel, SocksContext.UDP_IN_ENDPOINT), udp2raw);
//    }
//}
