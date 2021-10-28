package org.rx.net.nameserver;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
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
    static final String APP_NAME = "APP_NAME";

    @RequiredArgsConstructor
    static class DeregisterInfo implements Serializable {
        final String appName;
        final InetAddress ip;
    }

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
        return NQuery.of(rs.getClients()).groupBy(p -> isNull(p.attr(APP_NAME), "NOT_REG"), (k, p) -> Tuple.of(k, p.select(x -> x.getRemoteAddress().getAddress()).toList())).toMap(p -> p.left, p -> p.right);
    }

    public NameserverImpl(@NonNull NameserverConfig config) {
        this.config = config;
        dnsServer = new DnsServer(config.getDnsPort());
        dnsServer.setTtl(config.getDnsTtl());
        regEps.addAll(NQuery.of(config.getReplicaEndpoints()).select(Sockets::parseEndpoint).toList());

        rs = Remoting.listen(this, config.getRegisterPort());
        rs.onDisconnected.combine((s, e) -> {
            String appName = e.getClient().attr(APP_NAME);
            if (appName == null) {
                return;
            }

            doDeregister(e.getClient(), true);
        });

        ss = new UdpClient(getSyncPort());
        ss.onReceive.combine((s, e) -> {
            Object packet = e.getValue().packet;
            log("[{}] Replica {}", getSyncPort(), packet);
            if (!tryAs(packet, DeregisterInfo.class, p -> dnsServer.removeHosts(p.appName, Collections.singletonList(p.ip)))) {
                syncRegister((Collection<InetSocketAddress>) packet);
            }
        });
    }

    public void syncRegister(@NonNull Collection<InetSocketAddress> registerEndpoints) {
        if (!regEps.addAll(registerEndpoints) || !registerEndpoints.containsAll(regEps)) {
            return;
        }

        raiseEventAsync(Nameserver.EVENT_CLIENT_SYNC, new NEventArgs<>(regEps));
        for (InetSocketAddress ssAddr : registerEndpoints) {
            ss.sendAsync(Sockets.newEndpoint(ssAddr, getSyncPort()), registerEndpoints);
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
        ctx.getClient().attr(APP_NAME, appName);
        InetAddress addr = ctx.getClient().getRemoteAddress().getAddress();
        App.logMetric("remoteAddr", addr);
        dnsServer.addHosts(appName, weight, Collections.singletonList(addr));

        syncRegister(Arrays.toList(registerEndpoints));
        return config.getDnsPort();
    }

    @Override
    public void deregister() {
        RemotingContext ctx = RemotingContext.context();
        String appName = ctx.getClient().attr(APP_NAME);
        if (appName == null) {
            throw new InvalidException("Must register first");
        }

        doDeregister(ctx.getClient(), false);
    }

    private void doDeregister(RpcServerClient client, boolean isDisconnected) {
        String appName = client.attr(APP_NAME);
        InetAddress ip = client.getRemoteAddress().getAddress();
        //同app同ip多实例，比如k8s滚动更新
        if (NQuery.of(rs.getClients()).count(p -> eq(p.attr(APP_NAME), appName) && p.getRemoteAddress().getAddress().equals(ip)) == (isDisconnected ? 0 : 1)) {
            dnsServer.removeHosts(appName, Collections.singletonList(ip));
            syncDeregister(new DeregisterInfo(appName, ip));
        }
    }

    @Override
    public List<InetAddress> discover(@NonNull String appName) {
        RandomList<InetAddress> list = dnsServer.getHosts().get(appName);
        if (CollectionUtils.isEmpty(list)) {
            return Collections.emptyList();
        }
        //根据权重取2个
        return NQuery.of(list.next(), list.next()).distinct().toList();
    }
}
