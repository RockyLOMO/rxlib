package org.rx.net.nameserver;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.RandomList;
import org.rx.core.EventArgs;
import org.rx.core.EventTarget;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

public interface Nameserver extends EventTarget<Nameserver>, AutoCloseable {
    @RequiredArgsConstructor
    @Getter
    class AppChangedEventArgs extends EventArgs {
        private static final long serialVersionUID = -398674064775226514L;
        final String appName;
        final InetAddress ip;
        final boolean isUp;
    }

    /**
     * return active server endpoints of this nameserver
     */
    String EVENT_CLIENT_SYNC = "CLIENT_SYNC";
    String EVENT_APP_HOST_CHANGED = "APP_HOST_CHANGED";

    default int register(String appName, Set<InetSocketAddress> serverEndpoints) {
        return register(appName, RandomList.DEFAULT_WEIGHT, serverEndpoints);
    }

    /**
     * App register self, then return dns server proxy port
     *
     * @param appName
     * @param weight
     * @return
     */
    int register(String appName, int weight, Set<InetSocketAddress> serverEndpoints);

    void deregister();

    List<InetAddress> discover(String appName);

    List<InetAddress> discoverAll(String appName, boolean withoutCurrent);

    default void close() {
    }
}
