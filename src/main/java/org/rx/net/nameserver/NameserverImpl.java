package org.rx.net.nameserver;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.rx.bean.Tuple;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.App.tryAs;

public class NameserverImpl implements Nameserver {
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
        return NQuery.of(rs.getClients()).groupBy(p -> (String) p.userState, (k, p) -> Tuple.of(k, p.select(x -> x.getRemoteAddress().getAddress()).toList())).toMap(p -> p.left, p -> p.right);
    }

    public NameserverImpl(@NonNull NameserverConfig config) {
        this.config = config;
        dnsServer = new DnsServer(config.getDnsPort());
        dnsServer.setTtl(config.getDnsTtl());
        regEps.addAll(NQuery.of(config.getReplicaEndpoints()).select(Sockets::parseEndpoint).toList());

        rs = Remoting.listen(this, config.getRegisterPort());
        rs.onDisconnected.combine((s, e) -> {
            String appName = (String) e.getClient().userState;
            if (appName == null) {
                return;
            }

            InetAddress ip = e.getClient().getRemoteAddress().getAddress();
            dnsServer.removeHosts(appName, ip);
            syncDeregister(new DeregisterInfo(appName, ip));
        });

        ss = new UdpClient(getSyncPort());
        ss.onReceive.combine((s, e) -> {
            Object packet = e.getValue().packet;
//            System.out.println("udp" + packet);
            if (!tryAs(packet, DeregisterInfo.class, p -> dnsServer.removeHosts(p.appName, p.ip))) {
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
    public int register(@NonNull String appName, InetSocketAddress... registerEndpoints) {
        RemotingContext ctx = RemotingContext.context();
        ctx.getClient().userState = appName;
        dnsServer.addHosts(appName, ctx.getClient().getRemoteAddress().getAddress());

        syncRegister(Arrays.toList(registerEndpoints));
        return config.getDnsPort();
    }

    @Override
    public void deregister() {
        RemotingContext ctx = RemotingContext.context();
        String appName = (String) ctx.getClient().userState;
        if (appName == null) {
            throw new InvalidException("Must register first");
        }

        InetAddress ip = ctx.getClient().getRemoteAddress().getAddress();
        dnsServer.removeHosts(appName, ip);
        syncDeregister(new DeregisterInfo(appName, ip));
    }

    @Override
    public List<InetAddress> discover(@NonNull String appName) {
        return dnsServer.getHosts().get(appName);
    }
}
