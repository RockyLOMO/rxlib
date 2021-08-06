package org.rx.net.socks;

import io.netty.channel.Channel;
import org.rx.bean.Tuple;
import org.rx.core.ShellExecutor;
import org.rx.core.exception.ApplicationException;
import org.rx.util.function.BiFunc;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.App.tryClose;

public final class UdpManager {
    static final Map<InetSocketAddress, Tuple<Channel, ShellExecutor>> HOLD = new ConcurrentHashMap<>();

    public static Channel openChannel(InetSocketAddress incomingEp, BiFunc<InetSocketAddress, Channel> loadFn) {
        return HOLD.computeIfAbsent(incomingEp, k -> {
            try {
                return Tuple.of(loadFn.invoke(k), null);
            } catch (Throwable e) {
                throw ApplicationException.sneaky(e);
            }
        }).left;
    }

    public static void tun(InetSocketAddress incomingEp, ShellExecutor udpTun) {
        Tuple<Channel, ShellExecutor> tuple = HOLD.get(incomingEp);
        if (tuple == null) {
            return;
        }
        if (tuple.right != null) {
            tryClose(tuple.right);
        }
        tuple.right = udpTun;
    }

    public static void closeChannel(InetSocketAddress incomingEp) {
        Tuple<Channel, ShellExecutor> tuple = HOLD.remove(incomingEp);
        tryClose(tuple.right);
        tuple.left.close();
    }
}
