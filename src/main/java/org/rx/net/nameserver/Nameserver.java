package org.rx.net.nameserver;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.RandomList;
import org.rx.core.EventArgs;
import org.rx.core.EventTarget;
import org.rx.core.Extends;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface Nameserver extends EventTarget<Nameserver>, AutoCloseable {
    @RequiredArgsConstructor
    @Getter
    class AppChangedEventArgs extends EventArgs {
        private static final long serialVersionUID = -398674064775226514L;
        final String appName;
        final InetAddress address;
        final boolean isUp;
    }

    @RequiredArgsConstructor
    @Getter
    class DiscoverInfo implements Extends {
        private static final long serialVersionUID = 454367372507105015L;
        final InetAddress address;
        final String instanceId;
        final Map<String, Serializable> attributes;
    }

    /**
     * return active server endpoints of this nameserver
     */
    String EVENT_CLIENT_SYNC = "CLIENT_SYNC";
    String EVENT_APP_HOST_CHANGED = "APP_HOST_CHANGED";
    String APP_NAME_KEY = "app.name";

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

    <T extends Serializable> void attr(String appName, String key, T value);

    <T extends Serializable> T attr(String appName, String key);

    <T extends Serializable> void instanceAttr(String appName, String key, T value);

    <T extends Serializable> T instanceAttr(String appName, String key);

    List<InetAddress> discover(String appName);

    List<InetAddress> discoverAll(String appName, boolean exceptCurrent);

    List<DiscoverInfo> discover(String appName, List<String> instanceAttrKeys);

    List<DiscoverInfo> discoverAll(String appName, boolean exceptCurrent, List<String> instanceAttrKeys);

    default void close() {
    }
}
