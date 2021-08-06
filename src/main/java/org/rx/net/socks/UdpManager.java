package org.rx.net.socks;

import io.netty.channel.Channel;
import org.rx.bean.Tuple;
import org.rx.core.ShellExecutor;
import org.rx.core.exception.ApplicationException;
import org.rx.net.AuthenticEndpoint;
import org.rx.util.function.BiFunc;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.App.tryClose;

public final class UdpManager {
    static final Map<InetSocketAddress, Tuple<Channel, ShellExecutor>> HOLD = new ConcurrentHashMap<>();

    public static Channel openChannel(InetSocketAddress incomingEp, BiFunc<InetSocketAddress, Tuple<Channel, AuthenticEndpoint>> loadFn) {
        return HOLD.computeIfAbsent(incomingEp, k -> {
            try {
                Tuple<Channel, AuthenticEndpoint> tuple = loadFn.invoke(k);
                ShellExecutor udp2raw = null;
                if (tuple.right != null) {
                    udp2raw = new ShellExecutor(String.format("udp2raw_mp.exe -c -l0.0.0.0:%s -r%s -k \"%s\" --raw-mode faketcp --cipher-mode none --auth-mode simple",
                            ((InetSocketAddress) tuple.left.localAddress()).getPort(),
                            tuple.right.getEndpoint(), tuple.right.getUsername()));
                }
                return Tuple.of(tuple.left, udp2raw);
            } catch (Throwable e) {
                throw ApplicationException.sneaky(e);
            }
        }).left;
    }

    public static void closeChannel(InetSocketAddress incomingEp) {
        Tuple<Channel, ShellExecutor> tuple = HOLD.remove(incomingEp);
        tryClose(tuple.right);
        tuple.left.close();
    }
}
