package org.rx.net.nameserver;

import org.rx.bean.RandomList;
import org.rx.core.EventTarget;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

public interface Nameserver extends EventTarget<Nameserver>, AutoCloseable {
    /**
     * return active server endpoints of this nameserver
     */
    String EVENT_CLIENT_SYNC = "CLIENT_SYNC";

    default int register(String appName, InetSocketAddress... registerEndpoints) {
        return register(appName, RandomList.DEFAULT_WEIGHT, registerEndpoints);
    }

    /**
     * App register self, then return dns server proxy port
     *
     * @param appName
     * @param weight
     * @return
     */
    int register(String appName, int weight, InetSocketAddress... registerEndpoints);

    void deregister();

    List<InetAddress> discover(String appName);

    default void close() {
    }
}
