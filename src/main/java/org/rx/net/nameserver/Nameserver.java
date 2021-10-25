package org.rx.net.nameserver;

import org.rx.core.EventTarget;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

public interface Nameserver extends EventTarget<Nameserver>, AutoCloseable {
    /**
     * return active server endpoints of this nameserver
     */
    String EVENT_CLIENT_SYNC = "CLIENT_SYNC";

    /**
     * App register self, then return dns server proxy port
     *
     * @param appName
     * @return
     */
    int register(String appName, InetSocketAddress... registerEndpoints);

    void deregister();

    List<InetAddress> discover(String appName);

    default void close() {
    }
}
