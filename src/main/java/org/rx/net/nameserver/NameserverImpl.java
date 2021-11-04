package org.rx.net.nameserver;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.rx.bean.RandomList;
import org.rx.bean.Tuple;
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
        final String appName;
        final InetAddress ip;
    }

    static final String NAME = "nameserver";
    final NameserverConfig config;
    final RpcServer rs;
    @Getter
    final DnsServer dnsServer;
    final UdpClient ss;
    final Set<InetSocketAddress> regEps = ConcurrentHashMap.newKeySet();

    int getSyncPort() {
        return config.getRegisterPort();
    }

    public Map<String, List<InetAddress>> getInstances() {
        return NQuery.of(rs.getClients()).groupBy(p -> isNull(p.attr(), "NOT_REG"), (k, p) -> Tuple.of(k, p.select(x -> x.getRemoteAddress().getAddress()).toList())).toMap(p -> p.left, p -> p.right);
    }

    public NameserverImpl(@NonNull NameserverConfig config) {
        this.config = config;
        dnsServer = new DnsServer(config.getDnsPort());
        dnsServer.setTtl(config.getDnsTtl());
        regEps.addAll(NQuery.of(config.getReplicaEndpoints()).select(Sockets::parseEndpoint).toList());

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

    public synchronized void syncRegister(@NonNull Set<InetSocketAddress> registerEndpoints) {
        if (!regEps.addAll(registerEndpoints)
                && registerEndpoints.containsAll(regEps)
        ) {
            return;
        }

        dnsServer.addHosts(NAME, RandomList.DEFAULT_WEIGHT, NQuery.of(regEps).select(InetSocketAddress::getAddress).toList());
        raiseEventAsync(Nameserver.EVENT_CLIENT_SYNC, new NEventArgs<>(regEps));
        for (InetSocketAddress ssAddr : registerEndpoints) {
            ss.sendAsync(Sockets.newEndpoint(ssAddr, getSyncPort()), regEps);
        }
    }

    public void syncDeregister(@NonNull DeregisterInfo deregisterInfo) {
        for (InetSocketAddress ssAddr : regEps) {
            ss.sendAsync(Sockets.newEndpoint(ssAddr, getSyncPort()), deregisterInfo);
        }
    }

    @Override
    public int register(@NonNull String appName, int weight, InetSocketAddress... registerEndpoints) {
        App.logMetric("clientSize", rs.getClients().size());

        RemotingContext ctx = RemotingContext.context();
        ctx.getClient().attr(appName);
        InetAddress addr = ctx.getClient().getRemoteAddress().getAddress();
        App.logMetric("remoteAddr", addr);
        dnsServer.addHosts(appName, weight, Collections.singletonList(addr));

        syncRegister(new HashSet<>(Arrays.toList(registerEndpoints)));
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
}
