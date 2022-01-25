package org.rx.net.nameserver;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.rx.bean.RandomList;
import org.rx.core.App;
import org.rx.core.Arrays;
import org.rx.core.NEventArgs;
import org.rx.core.NQuery;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.dns.DnsServer;
import org.rx.net.rpc.*;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.App.*;

public class NameserverImpl implements Nameserver {
    @RequiredArgsConstructor
    @ToString
    static class DeregisterInfo implements Serializable {
        private static final long serialVersionUID = 713672672746841635L;
        final String appName;
        final InetAddress ip;
    }

    static final String NAME = "nameserver";
    final NameserverConfig config;
    final RpcServer rs;
    @Getter
    final DnsServer dnsServer;
    final UdpClient ss;
    final Set<InetSocketAddress> svrEps = ConcurrentHashMap.newKeySet();

    int getSyncPort() {
        if (config.getSyncPort() > 0) {
            return config.getSyncPort();
        }
        return config.getRegisterPort();
    }

    public Map<String, List<InetAddress>> getInstances() {
        return NQuery.of(rs.getClients()).groupByIntoMap(p -> isNull(p.attr(), "NOT_REG"), (k, p) -> p.select(x -> x.getRemoteAddress().getAddress()).toList());
    }

    public NameserverImpl(@NonNull NameserverConfig config) {
        this.config = config;
        dnsServer = new DnsServer(config.getDnsPort());
        dnsServer.setTtl(config.getDnsTtl());
        svrEps.addAll(NQuery.of(config.getReplicaEndpoints()).select(Sockets::parseEndpoint).selectMany(Sockets::allEndpoints).toList());

        rs = Remoting.listen(this, config.getRegisterPort());
        rs.onDisconnected.combine((s, e) -> {
            String appName = e.getClient().attr();
            if (appName == null) {
                return;
            }

            doDeregister(appName, e.getClient().getRemoteAddress().getAddress(), true, true);
        });

        ss = new UdpClient(getSyncPort());
        ss.onReceive.combine((s, e) -> {
            Object packet = e.getValue().packet;
            log("[{}] Replica {}", getSyncPort(), packet);
            if (!tryAs(packet, DeregisterInfo.class, p -> doDeregister(p.appName, p.ip, false, false))) {
                syncRegister((Set<InetSocketAddress>) packet);
            }
        });
    }

    public synchronized void syncRegister(@NonNull Set<InetSocketAddress> serverEndpoints) {
        if (!svrEps.addAll(serverEndpoints) && serverEndpoints.containsAll(svrEps)) {
            return;
        }

        dnsServer.addHosts(NAME, RandomList.DEFAULT_WEIGHT, NQuery.of(svrEps).select(InetSocketAddress::getAddress).toList());
        raiseEventAsync(Nameserver.EVENT_CLIENT_SYNC, new NEventArgs<>(svrEps));
        for (InetSocketAddress ssAddr : serverEndpoints) {
            ss.sendAsync(Sockets.newEndpoint(ssAddr, getSyncPort()), svrEps);
        }
    }

    public void syncDeregister(@NonNull DeregisterInfo deregisterInfo) {
        for (InetSocketAddress ssAddr : svrEps) {
            ss.sendAsync(Sockets.newEndpoint(ssAddr, getSyncPort()), deregisterInfo);
        }
    }

    @Override
    public int register(@NonNull String appName, int weight, InetSocketAddress... serverEndpoints) {
        App.logMetric("clientSize", rs.getClients().size());

        RemotingContext ctx = RemotingContext.context();
        ctx.getClient().attr(appName);
        InetAddress addr = ctx.getClient().getRemoteAddress().getAddress();
        App.logMetric("remoteAddr", addr);
        dnsServer.addHosts(appName, weight, Collections.singletonList(addr));

        syncRegister(new HashSet<>(Arrays.toList(serverEndpoints)));
        return config.getDnsPort();
    }

    @Override
    public void deregister() {
        RemotingContext ctx = RemotingContext.context();
        String appName = ctx.getClient().attr();
        if (appName == null) {
            throw new InvalidException("Must register first");
        }

        doDeregister(appName, ctx.getClient().getRemoteAddress().getAddress(), false, true);
    }

    private void doDeregister(String appName, InetAddress ip, boolean isDisconnected, boolean shouldSync) {
        //同app同ip多实例，比如k8s滚动更新
        int c = NQuery.of(rs.getClients()).count(p -> eq(p.attr(), appName) && p.getRemoteAddress().getAddress().equals(ip));
        if (c == (isDisconnected ? 0 : 1)) {
            App.log("deregister {}", appName);
            dnsServer.removeHosts(appName, Collections.singletonList(ip));
            if (shouldSync) {
                syncDeregister(new DeregisterInfo(appName, ip));
            }
        }
    }

    @Override
    public List<InetAddress> discover(@NonNull String appName) {
        return dnsServer.getHosts(appName);
    }

    @Override
    public List<InetAddress> discoverAll(@NonNull String appName) {
        return dnsServer.getAllHosts(appName);
    }
}
