package org.rx.net.nameserver;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.bean.RandomList;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.util.function.Action;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.rx.core.Sys.*;
import static org.rx.core.Extends.*;

@Slf4j
public final class NameserverClient extends Disposable {
    @RequiredArgsConstructor
    static class NsInfo {
        final InetSocketAddress registerEndpoint;
        Nameserver ns;
        Future<?> healthTask;
        volatile Integer dnsPort;
    }

    static final int DEFAULT_RETRY_PERIOD = 1000;
    static final int DEFAULT_RETRY = 2;
    static final int DEFAULT_HEALTH_PERIOD = 120 * 1000;
    static final List<RandomList<NsInfo>> group = new CopyOnWriteArrayList<>();
    static final ResetEventWait syncRoot = new ResetEventWait();

    static void reInject() {
        Tasks.setTimeout(() -> {
            Linq<NsInfo> q = Linq.from(group).selectMany(RandomList::aliveList).where(p -> p.dnsPort != null);
            if (!q.any()) {
                log.warn("At least one dns server that required");
                return;
            }

            List<InetSocketAddress> ns = q.select(p -> Sockets.newEndpoint(p.registerEndpoint, p.dnsPort)).distinct().toList();
            Sockets.injectNameService(ns);
            log.info("inject ns {}", toJsonString(ns));
            syncRoot.set();
        }, Constants.DEFAULT_INTERVAL, NameserverClient.class, TimeoutFlag.REPLACE.flags());
    }

    public final Delegate<Nameserver, Nameserver.AppChangedEventArgs> onAppAddressChanged = Delegate.create();
    @Getter
    final String appName;
    final RandomList<NsInfo> hold = new RandomList<>();
    final Set<InetSocketAddress> svrEps = ConcurrentHashMap.newKeySet();

    public Set<InetSocketAddress> registerEndpoints() {
        return Linq.from(hold).select(p -> p.registerEndpoint).toSet();
    }

    public Set<InetSocketAddress> discoveryEndpoints() {
        return Linq.from(hold).where(p -> p.dnsPort != null).select(p -> Sockets.newEndpoint(p.registerEndpoint, p.dnsPort)).toSet();
    }

    public NameserverClient(String appName) {
        this.appName = appName;
        group.add(hold);
    }

    @Override
    protected void freeObjects() {
        group.remove(hold);
        for (NsInfo tuple : hold) {
            tryClose(tuple.ns);
        }
    }

    public void waitInject() throws TimeoutException {
        waitInject(30 * 1000);
    }

    public void waitInject(long timeout) throws TimeoutException {
        syncRoot.waitOne(timeout);
        syncRoot.set();
    }

    public CompletableFuture<?> registerAsync(@NonNull String... registerEndpoints) {
        if (registerEndpoints.length == 0) {
            throw new InvalidException("At least one server that required");
        }

        return registerAsync(Linq.from(registerEndpoints).select(Sockets::parseEndpoint).toSet());
    }

    public CompletableFuture<?> registerAsync(@NonNull Set<InetSocketAddress> registerEndpoints) {
        if (registerEndpoints.isEmpty()) {
            throw new InvalidException("At least one server that required");
        }
        svrEps.addAll(Linq.from(registerEndpoints).selectMany(Sockets::allEndpoints).toSet());

        return Tasks.runAsync(() -> {
            for (InetSocketAddress regEp : svrEps) {
                synchronized (hold) {
                    if (Linq.from(hold).any(p -> eq(p.registerEndpoint, regEp))) {
                        continue;
                    }

                    NsInfo tuple = new NsInfo(regEp);
                    hold.add(tuple);
                    Action handshake = () -> {
                        try {
                            Integer lastDp = tuple.dnsPort;
                            if (eq(tuple.dnsPort = tuple.ns.register(appName, svrEps), lastDp)) {
                                log.debug("login ns ok {} -> {}", regEp, tuple.dnsPort);
                                return;
                            }
                            log.info("login ns {} -> {} PREV={}", regEp, tuple.dnsPort, lastDp);
                            tuple.ns.instanceAttr(appName, RxConfig.ConfigNames.APP_ID, RxConfig.INSTANCE.getId());
                            reInject();
                        } catch (Throwable e) {
                            log.debug("login error", e);
                            Tasks.setTimeout(() -> {
                                tuple.dnsPort = tuple.ns.register(appName, svrEps);
                                tuple.ns.instanceAttr(appName, RxConfig.ConfigNames.APP_ID, RxConfig.INSTANCE.getId());
                                reInject();
                                asyncContinue(false);
                            }, DEFAULT_RETRY_PERIOD, appName, TimeoutFlag.SINGLE.flags(TimeoutFlag.PERIOD));
                        }
                    };
                    RpcClientConfig<Nameserver> config = RpcClientConfig.statefulMode(regEp, 0);
                    config.setInitHandler((ns, rc) -> {
                        rc.onConnected.combine((s, e) -> {
                            hold.setWeight(tuple, RandomList.DEFAULT_WEIGHT);
                            reInject();
                        });
                        rc.onDisconnected.combine((s, e) -> {
                            hold.setWeight(tuple, 0);
                            reInject();
                        });
                        rc.onReconnecting.combine((s, e) -> {
                            if (svrEps.addAll(Linq.from(registerEndpoints).selectMany(Sockets::allEndpoints).toSet())) {
                                registerAsync(svrEps);
                            }
                        });
                        rc.onReconnected.combine((s, e) -> {
                            tuple.dnsPort = null;
                            handshake.apply();
                        });
                        ns.<NEventArgs<Set<InetSocketAddress>>>attachEvent(Nameserver.EVENT_CLIENT_SYNC, (s, e) -> {
                            log.info("sync server endpoints: {}", toJsonString(e.getValue()));
                            if (e.getValue().isEmpty()) {
                                return;
                            }

                            registerAsync(e.getValue());
                        }, false);
                        //onAppAddressChanged for arg#1 not work
                        ns.<Nameserver.AppChangedEventArgs>attachEvent(Nameserver.EVENT_APP_ADDRESS_CHANGED, (s, e) -> {
                            log.info("app address changed: {} -> {}", e.getAppName(), e.getAddress());
                            onAppAddressChanged.invoke(s, e);
                        }, false);
                    });
                    tuple.ns = Remoting.createFacade(Nameserver.class, config);
                    tuple.healthTask = Tasks.schedulePeriod(handshake, DEFAULT_HEALTH_PERIOD);
                    handshake.apply();
                }
            }
        });
    }

    public CompletableFuture<?> deregisterAsync() {
        return Tasks.runAsync(() -> {
            for (NsInfo tuple : hold) {
                sneakyInvoke(() -> tuple.ns.deregister(), DEFAULT_RETRY);
            }
        });
    }

    public List<InetAddress> discover(@NonNull String appName) {
        return hold.next().ns.discover(appName);
    }

    public List<InetAddress> discoverAll(@NonNull String appName, boolean exceptCurrent) {
        return hold.next().ns.discoverAll(appName, exceptCurrent);
    }

    public List<Nameserver.InstanceInfo> discover(@NonNull String appName, List<String> instanceAttrKeys) {
        return hold.next().ns.discover(appName, instanceAttrKeys);
    }

    public List<Nameserver.InstanceInfo> discoverAll(@NonNull String appName, boolean exceptCurrent, List<String> instanceAttrKeys) {
        return hold.next().ns.discoverAll(appName, exceptCurrent, instanceAttrKeys);
    }
}
