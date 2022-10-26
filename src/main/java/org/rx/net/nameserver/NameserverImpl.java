package org.rx.net.nameserver;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.RandomList;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.dns.DnsServer;
import org.rx.net.rpc.*;
import org.rx.net.transport.TcpServer;
import org.rx.net.transport.UdpClient;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Extends.*;

@Slf4j
public class NameserverImpl implements Nameserver {
    @RequiredArgsConstructor
    @ToString
    static class DeregisterInfo implements Serializable {
        private static final long serialVersionUID = 713672672746841635L;
        final String appName;
        final InetAddress address;
    }

    static final String NAME = "nameserver";
    final NameserverConfig config;
    final TcpServer rs;
    @Getter
    final DnsServer dnsServer;
    @Setter
    long syncDelay = 500;
    final UdpClient ss;
    final Set<InetSocketAddress> svrEps = ConcurrentHashMap.newKeySet();
    //key String -> appName, InetAddress -> instance addr
    final Map<Object, Map<String, Serializable>> attrs = new ConcurrentHashMap<>();

    int getSyncPort() {
        if (config.getSyncPort() > 0) {
            return config.getSyncPort();
        }
        return config.getRegisterPort();
    }

    public Map<String, List<InstanceInfo>> getInstances() {
        return Linq.from(rs.getClients().values()).groupByIntoMap(p -> ifNull(p.attr(APP_NAME_KEY), "NOT_REG"),
                (k, p) -> getDiscoverInfos(p.select(x -> x.getRemoteEndpoint().getAddress()).toList(), Collections.emptyList()));
    }

    public NameserverImpl(@NonNull NameserverConfig config) {
        this.config = config;
        dnsServer = new DnsServer(config.getDnsPort());
        dnsServer.setTtl(config.getDnsTtl());
        svrEps.addAll(Linq.from(config.getReplicaEndpoints()).select(Sockets::parseEndpoint).selectMany(Sockets::allEndpoints).toList());

        rs = Remoting.register(this, config.getRegisterPort(), false);
        rs.onDisconnected.combine((s, e) -> {
            String appName = e.getClient().attr(APP_NAME_KEY);
            if (appName == null) {
                return;
            }

            doDeregister(appName, e.getClient().getRemoteEndpoint().getAddress(), true, true);
        });
        rs.onPing.combine((s, e) -> attrs(e.getClient().getRemoteEndpoint().getAddress())
                .put("ping", String.format("%dms", (System.currentTimeMillis() - e.getValue().getTimestamp()) * 2)));

        ss = new UdpClient(getSyncPort());
        ss.onReceive.combine((s, e) -> {
            Object packet = e.getValue().packet;
            log.info("[{}] Replica {}", getSyncPort(), packet);
            if (!tryAs(packet, Map.class, p -> {
                Map<Object, Map<String, Serializable>> sync = (Map<Object, Map<String, Serializable>>) p;
                for (Map.Entry<Object, Map<String, Serializable>> entry : sync.entrySet()) {
                    attrs(entry.getKey()).putAll(entry.getValue());
                }
            }) && !tryAs(packet, DeregisterInfo.class, p -> doDeregister(p.appName, p.address, false, false))) {
                syncRegister((Set<InetSocketAddress>) packet);
            }
        });
    }

    public synchronized void syncRegister(@NonNull Set<InetSocketAddress> serverEndpoints) {
        if (!svrEps.addAll(serverEndpoints) && serverEndpoints.containsAll(svrEps)) {
            return;
        }

        dnsServer.addHosts(NAME, RandomList.DEFAULT_WEIGHT, Linq.from(svrEps).select(InetSocketAddress::getAddress).toList());
        raiseEventAsync(EVENT_CLIENT_SYNC, new NEventArgs<>(svrEps));
        Tasks.setTimeout(() -> {
            for (InetSocketAddress ssAddr : serverEndpoints) {
                ss.sendAsync(Sockets.newEndpoint(ssAddr, getSyncPort()), svrEps);
            }
        }, syncDelay, svrEps, TimeoutFlag.REPLACE.flags());
    }

    public void syncDeregister(@NonNull DeregisterInfo deregisterInfo) {
        Tasks.setTimeout(() -> {
            for (InetSocketAddress ssAddr : svrEps) {
                ss.sendAsync(Sockets.newEndpoint(ssAddr, getSyncPort()), deregisterInfo);
            }
        }, syncDelay, DeregisterInfo.class, TimeoutFlag.REPLACE.flags());
    }

    public void syncAttributes() {
        Tasks.setTimeout(() -> {
            for (InetSocketAddress ssAddr : svrEps) {
                ss.sendAsync(Sockets.newEndpoint(ssAddr, getSyncPort()), attrs);
            }
        }, syncDelay, attrs, TimeoutFlag.REPLACE.flags());
    }

    @Override
    public int register(@NonNull String appName, int weight, Set<InetSocketAddress> serverEndpoints) {
        App.logCtx("clientSize", rs.getClients().size());

        RemotingContext ctx = RemotingContext.context();
        ctx.getClient().attr(APP_NAME_KEY, appName);
        InetAddress addr = ctx.getClient().getRemoteEndpoint().getAddress();
        App.logCtx("remoteAddr", addr);
        doRegister(appName, weight, addr);

        syncRegister(serverEndpoints);
        return config.getDnsPort();
    }

    void doRegister(String appName, int weight, InetAddress addr) {
        if (dnsServer.addHosts(appName, weight, Collections.singletonList(addr))) {
            raiseEventAsync(EVENT_APP_ADDRESS_CHANGED, new AppChangedEventArgs(appName, addr, true, attrs(addr)));
        }
    }

    @Override
    public void deregister() {
        RemotingContext ctx = RemotingContext.context();
        String appName = ctx.getClient().attr(APP_NAME_KEY);
        if (appName == null) {
            throw new InvalidException("Must register first");
        }

        doDeregister(appName, ctx.getClient().getRemoteEndpoint().getAddress(), false, true);
    }

    void doDeregister(String appName, InetAddress addr, boolean isDisconnected, boolean shouldSync) {
        //同app同ip多实例，比如k8s滚动更新
        int c = Linq.from(rs.getClients().values()).count(p -> eq(p.attr(APP_NAME_KEY), appName) && p.getRemoteEndpoint().getAddress().equals(addr));
        if (c == (isDisconnected ? 0 : 1)) {
            log.info("deregister {}", appName);
            if (dnsServer.removeHosts(appName, Collections.singletonList(addr))) {
                raiseEventAsync(EVENT_APP_ADDRESS_CHANGED, new AppChangedEventArgs(appName, addr, false, attrs(addr)));
            }
            if (shouldSync) {
                syncDeregister(new DeregisterInfo(appName, addr));
            }
        }
    }

    @Override
    public <T extends Serializable> void attr(String appName, String key, T value) {
        attrs(appName).put(key, value);
        syncAttributes();
    }

    @Override
    public <T extends Serializable> T attr(String appName, String key) {
        return (T) attrs(appName).get(key);
    }

    Map<String, Serializable> attrs(Object key) {
        return attrs.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
    }

    @Override
    public <T extends Serializable> void instanceAttr(String appName, String key, T value) {
        RemotingContext ctx = RemotingContext.context();
        attrs(ctx.getClient().getRemoteEndpoint().getAddress()).put(key, value);
        syncAttributes();
    }

    @Override
    public <T extends Serializable> T instanceAttr(String appName, String key) {
        RemotingContext ctx = RemotingContext.context();
        return (T) attrs(ctx.getClient().getRemoteEndpoint().getAddress()).get(key);
    }

    @Override
    public List<InetAddress> discover(@NonNull String appName) {
        return dnsServer.getHosts(appName);
    }

    @Override
    public List<InetAddress> discoverAll(@NonNull String appName, boolean exceptCurrent) {
        List<InetAddress> hosts = dnsServer.getAllHosts(appName);
        if (exceptCurrent) {
            RemotingContext ctx = RemotingContext.context();
            hosts.remove(ctx.getClient().getRemoteEndpoint().getAddress());
        }
        return hosts;
    }

    @Override
    public List<InstanceInfo> discover(@NonNull String appName, List<String> instanceAttrKeys) {
        List<InetAddress> hosts = dnsServer.getHosts(appName);
        return getDiscoverInfos(hosts, instanceAttrKeys);
    }

    @Override
    public List<InstanceInfo> discoverAll(@NonNull String appName, boolean exceptCurrent, List<String> instanceAttrKeys) {
        List<InetAddress> hosts = dnsServer.getAllHosts(appName);
        if (exceptCurrent) {
            RemotingContext ctx = RemotingContext.context();
            hosts.remove(ctx.getClient().getRemoteEndpoint().getAddress());
        }
        return getDiscoverInfos(hosts, instanceAttrKeys);
    }

    List<InstanceInfo> getDiscoverInfos(List<InetAddress> hosts, List<String> instanceAttrKeys) {
        return Linq.from(hosts).select(p -> {
            Map<String, Serializable> attrs = attrs(p);
            return new InstanceInfo(p, (String) attrs.get(RxConfig.ConfigNames.APP_ID),
                    Linq.from(!CollectionUtils.isEmpty(instanceAttrKeys) ? instanceAttrKeys : attrs.keySet()).toMap(x -> x, attrs::get));
        }).toList();
    }
}
