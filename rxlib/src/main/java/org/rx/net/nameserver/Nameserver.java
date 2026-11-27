package org.rx.net.nameserver;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.RandomList;
import org.rx.core.EventArgs;
import org.rx.core.EventPublisher;
import org.rx.core.Extends;
import org.rx.core.RxConfig;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface Nameserver extends EventPublisher<Nameserver>, AutoCloseable {
    @Getter
    class AppChangedEventArgs extends EventArgs {
        private static final long serialVersionUID = -398674064775226514L;
        final String appName;
        final InetAddress address;
        final boolean isUp;
        final String instanceId;
        final Map<String, Serializable> attributes;

        AppChangedEventArgs(String appName, InetAddress address, boolean isUp, Map<String, Serializable> attributes) {
            this(appName, address, isUp, null, attributes);
        }

        AppChangedEventArgs(String appName, InetAddress address, boolean isUp, String instanceId, Map<String, Serializable> attributes) {
            this.appName = appName;
            this.address = address;
            this.isUp = isUp;
            this.attributes = attributes;
            this.instanceId = instanceId != null ? instanceId
                    : (attributes == null ? null : (String) attributes.get(RxConfig.ConfigNames.APP_ID));
        }
    }

    @RequiredArgsConstructor
    @Getter
    class InstanceInfo implements Extends {
        private static final long serialVersionUID = 454367372507105015L;
        final InetAddress address;
        final String instanceId;
        final Map<String, Serializable> attributes;
    }

    /**
     * return active server endpoints of this nameserver
     */
    String EVENT_CLIENT_SYNC = "CLIENT_SYNC";
    String EVENT_APP_ADDRESS_CHANGED = "APP_ADDRESS_CHANGED";
    String EVENT_APP_ATTRS_CHANGED = "APP_ATTRS_CHANGED";
    String APP_NAME_KEY = "app.name";
    String INSTANCE_ID_KEY = "instance.id";
    String PUBLIC_IP_KEY = "publicIp";

    default int register(String appName, Set<InetSocketAddress> serverEndpoints) {
        return register(appName, RandomList.DEFAULT_WEIGHT, serverEndpoints);
    }

    /**
     * App register self, then return dns server proxy port
     *
     * @param appName
     * @param weight
     * @return dnsPort
     */
    int register(String appName, int weight, Set<InetSocketAddress> serverEndpoints);

    /**
     * App register self with an explicit instance identity (independent of NAT / source IP).
     */
    default int register(String appName, int weight, String instanceId, Set<InetSocketAddress> serverEndpoints) {
        return register(appName, weight, serverEndpoints);
    }

    void deregister();

    <T extends Serializable> void attr(String appName, String key, T value);

    <T extends Serializable> T attr(String appName, String key);

    <T extends Serializable> void instanceAttr(String appName, String key, T value);

    <T extends Serializable> T instanceAttr(String appName, String key);

    List<InetAddress> discover(String appName);

    List<InetAddress> discoverAll(String appName, boolean exceptCurrent);

    List<InstanceInfo> discover(String appName, List<String> instanceAttrKeys);

    List<InstanceInfo> discoverAll(String appName, boolean exceptCurrent, List<String> instanceAttrKeys);

    /** Internal authenticated peer-to-peer incremental replication RPC. */
    default NameserverReplicaProtocol.ReplicaAck replicaApplyDelta(NameserverReplicaProtocol.ReplicaDeltaRequest request) {
        throw new UnsupportedOperationException("Replication is not supported");
    }

    /** Internal authenticated peer-to-peer paged full snapshot replication RPC. */
    default NameserverReplicaProtocol.ReplicaAck replicaApplySnapshotPage(NameserverReplicaProtocol.ReplicaSnapshotPage page) {
        throw new UnsupportedOperationException("Replication is not supported");
    }

    default void close() {
    }
}
