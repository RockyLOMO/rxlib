package org.rx.net.http.tunnel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import org.rx.core.Tasks;
import org.rx.io.IOStream;
import org.rx.io.Bytes;
import org.rx.net.http.HttpClient;
import org.rx.net.socks.SocksProxyServer;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Client {
    @RequiredArgsConstructor
    class SocksContext {
        private final Channel inboundChannel;
        private final Map<String, Object> outboundForms = new ConcurrentHashMap<>();
        private final HttpClient outboundOfferClient = new HttpClient(timeWaitSeconds * 2);
        private final HttpClient outboundPollClient = new HttpClient(timeWaitSeconds * 2);

        public SocksContext(String appName, String inboundSocksId, String remoteEndpoint, Channel inboundChannel) {
            this.inboundChannel = inboundChannel;
            outboundForms.put("appName", appName);
            outboundForms.put("socksId", inboundSocksId);
            outboundForms.put("endpoint", remoteEndpoint);
            Tasks.schedulePeriod(this::backendPoll, 100);
        }

        public void backendOffer(IOStream<?, ?> binary) {
            outboundOfferClient.post(String.format("%s/apix/directOffer", serverUrl),
                    outboundForms, Collections.singletonMap("binary", binary)).toString();
        }

        protected void backendPoll() {
            outboundPollClient.post(String.format("%s/apix/directPoll", serverUrl), outboundForms).handle(in -> {
                ByteBuf buf = Bytes.copyInputStream(in);
                try {
                    inboundChannel.writeAndFlush(buf);
                } finally {
                    buf.release();
                }
            });
        }
    }

    private int timeWaitSeconds = 20;
    private final String serverUrl;
    private final SocksProxyServer proxyServer;
    private final Map<Integer, Map<String, SocksContext>> holds = new ConcurrentHashMap<>();

    public Client(String serverUrl, int listenPort) {
        this.serverUrl = serverUrl;
//        SocksConfig config = new SocksConfig();
//        config.setListenPort(listenPort);
//        config.setUpstreamSupplier(null);
//        config.setUpstreamPreReconnect(null);
//        proxyServer = new SocksProxyServer(config);
        proxyServer = null;
    }
//
//    public void xxxx() {
//
//    }
}
