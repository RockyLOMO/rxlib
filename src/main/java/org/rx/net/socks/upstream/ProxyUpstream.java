//package org.rx.net.socks.upstream;
//
//import io.netty.channel.Channel;
//import lombok.NonNull;
//import org.rx.net.support.UnresolvedEndpoint;
//
//import java.net.InetSocketAddress;
//
//public class ProxyUpstream extends Upstream {
//    protected InetSocketAddress proxyEndpoint;
//
//    public ProxyUpstream(@NonNull UnresolvedEndpoint dstEp, @NonNull InetSocketAddress proxyEp) {
//        destination = dstEp;
//        proxyEndpoint = proxyEp;
//    }
//
//    @Override
//    public void initChannel(Channel channel) {
//        //do nth.
//    }
//}
