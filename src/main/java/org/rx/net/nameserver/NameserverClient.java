package org.rx.net.nameserver;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.bean.BiTuple;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import static org.rx.core.App.*;
import static org.rx.core.Extends.*;

@Slf4j
public final class NameserverClient extends Disposable {
    static final int DEFAULT_RETRY_PERIOD = 1000;
    static final int DEFAULT_RETRY = 2;
    static final List<RandomList<BiTuple<InetSocketAddress, Nameserver, Integer>>> LISTS = new CopyOnWriteArrayList<>();
    static final ResetEventWait syncRoot = new ResetEventWait();

    static void reInject() {
        Tasks.setTimeout(() -> {
            Linq<BiTuple<InetSocketAddress, Nameserver, Integer>> q = Linq.from(LISTS).selectMany(RandomList::aliveList).where(p -> p.right != null);
            if (!q.any()) {
                log.warn("At least one dns server that required");
                return;
            }

            List<InetSocketAddress> ns = q.select(p -> Sockets.newEndpoint(p.left, p.right)).distinct().toList();
            Sockets.injectNameService(ns);
            log.info("inject ns {}", toJsonString(ns));
            syncRoot.set();
        }, Constants.DEFAULT_INTERVAL, NameserverClient.class, TimeoutFlag.REPLACE);
    }

    public final Delegate<Nameserver, Nameserver.AppChangedEventArgs> onAppAddressChanged = Delegate.create();
    @Getter
    final String appName;
    final RandomList<BiTuple<InetSocketAddress, Nameserver, Integer>> hold = new RandomList<>();
    final Map<String, Future<?>> delayTasks = new ConcurrentHashMap<>();
    final Set<InetSocketAddress> svrEps = ConcurrentHashMap.newKeySet();

    public Set<InetSocketAddress> registerEndpoints() {
        return Linq.from(hold).select(p -> p.left).toSet();
    }

    public Set<InetSocketAddress> discoveryEndpoints() {
        return Linq.from(hold).where(p -> p.right != null).select(p -> Sockets.newEndpoint(p.left, p.right)).toSet();
    }

    public NameserverClient(String appName) {
        this.appName = appName;
        LISTS.add(hold);
    }

    @Override
    protected void freeObjects() {
        LISTS.remove(hold);
        for (BiTuple<InetSocketAddress, Nameserver, Integer> tuple : hold) {
            tryClose(tuple.middle);
        }
    }

    public void wait4Inject() throws TimeoutException {
        wait4Inject(30 * 1000);
    }

    public void wait4Inject(long timeout) throws TimeoutException {
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
                    if (Linq.from(hold).any(p -> eq(p.left, regEp))) {
                        continue;
                    }

                    BiTuple<InetSocketAddress, Nameserver, Integer> tuple = BiTuple.of(regEp, null, null);
                    hold.add(tuple);
                    Action doReg = () -> {
                        try {
                            tuple.right = tuple.middle.register(appName, svrEps);
                            tuple.middle.instanceAttr(appName, RxConfig.ConfigNames.APP_ID, RxConfig.INSTANCE.getId());
                            reInject();
                        } catch (Throwable e) {
                            delayTasks.computeIfAbsent(appName, k -> Tasks.setTimeout(() -> {
                                tuple.right = tuple.middle.register(appName, svrEps);
                                tuple.middle.instanceAttr(appName, RxConfig.ConfigNames.APP_ID, RxConfig.INSTANCE.getId());
                                delayTasks.remove(appName); //优先
                                reInject();
                                asyncContinue(false);
                            }, DEFAULT_RETRY_PERIOD, null, TimeoutFlag.PERIOD));
                        }
                    };
                    tuple.middle = Remoting.create(Nameserver.class, RpcClientConfig.statefulMode(regEp, 0),
                            (ns, rc) -> {
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
                                    tuple.right = null;
                                    doReg.invoke();
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
                    doReg.invoke();
                }
            }
        });
    }

    public CompletableFuture<?> deregisterAsync() {
        return Tasks.runAsync(() -> {
            for (BiTuple<InetSocketAddress, Nameserver, Integer> tuple : hold) {
                sneakyInvoke(() -> tuple.middle.deregister(), DEFAULT_RETRY);
            }
        });
    }

    public List<InetAddress> discover(@NonNull String appName) {
        return hold.next().middle.discover(appName);
    }

    public List<InetAddress> discoverAll(@NonNull String appName, boolean exceptCurrent) {
        return hold.next().middle.discoverAll(appName, exceptCurrent);
    }

    public List<Nameserver.InstanceInfo> discover(@NonNull String appName, List<String> instanceAttrKeys) {
        return hold.next().middle.discover(appName, instanceAttrKeys);
    }

    public List<Nameserver.InstanceInfo> discoverAll(@NonNull String appName, boolean exceptCurrent, List<String> instanceAttrKeys) {
        return hold.next().middle.discoverAll(appName, exceptCurrent, instanceAttrKeys);
    }
}
