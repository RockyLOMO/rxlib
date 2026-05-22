package org.rx.net;

import io.netty.channel.Channel;
import org.rx.core.RxConfig;
import org.rx.util.function.QuadraAction;
import org.rx.util.function.TripleAction;

public final class TcpBackpressureManager {
    public static final TcpBackpressureManager DEFAULT = new TcpBackpressureManager();

    private TcpBackpressureManager() {
    }

    public boolean install(Channel inbound, Channel outbound) {
        return install(inbound, outbound, (in, out) -> Sockets.disableAutoRead(in),
                (in, out, e) -> Sockets.enableAutoRead(in));
    }

    public boolean install(Channel inbound, Channel outbound,
                           TripleAction<Channel, Channel> onBackpressureStart,
                           QuadraAction<Channel, Channel, Throwable> onBackpressureEnd) {
        NetworkTrafficConfig config = RxConfig.INSTANCE.getNet().getGlobalTraffic();
        if (config != null && !config.isTcpBackpressureEnabled()) {
            return false;
        }
        if (outbound == null || outbound.pipeline().get(BackpressureHandler.class) != null) {
            return false;
        }
        BackpressureHandler.install(inbound, outbound, onBackpressureStart, onBackpressureEnd);
        return outbound.pipeline().get(BackpressureHandler.class) != null;
    }
}
