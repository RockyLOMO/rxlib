package org.rx.net.nameserver;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.BiTuple;
import org.rx.bean.RandomList;
import org.rx.core.Disposable;
import org.rx.core.NEventArgs;
import org.rx.core.NQuery;
import org.rx.core.Tasks;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.rx.core.App.*;

@Slf4j
public final class NameserverClient extends Disposable {
    static final int DEFAULT_RETRY = 2;
    static final List<RandomList<BiTuple<InetSocketAddress, Nameserver, Integer>>> LISTS = new CopyOnWriteArrayList<>();

    static void reInject() {
        NQuery<BiTuple<InetSocketAddress, Nameserver, Integer>> q = NQuery.of(LISTS).selectMany(RandomList::aliveList).where(p -> p.right != null);
        if (!q.any()) {
//            throw new InvalidException("At least one dns server that required");
            log.warn("At least one dns server that required");
            return;
        }

        InetSocketAddress[] ns = q.select(p -> Sockets.newEndpoint(p.left, p.right)).distinct().toArray();
        log.info("inject ns {}", toJsonString(ns));
        Sockets.injectNameService(ns);
    }

    @Getter
    final String appName;
    final RandomList<BiTuple<InetSocketAddress, Nameserver, Integer>> hold = new RandomList<>();

    public Set<InetSocketAddress> registerEndpoints() {
        return NQuery.of(hold).select(p -> p.left).toSet();
    }

    public Set<InetSocketAddress> discoveryEndpoints() {
        return NQuery.of(hold).where(p -> p.right != null).select(p -> Sockets.newEndpoint(p.left, p.right)).toSet();
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

    public CompletableFuture<?> registerAsync(@NonNull String... registerEndpoints) {
        if (registerEndpoints.length == 0) {
            throw new InvalidException("At least one server that required");
        }

        return registerAsync(NQuery.of(registerEndpoints).select(Sockets::parseEndpoint).toArray());
    }

    public CompletableFuture<?> registerAsync(@NonNull InetSocketAddress... registerEndpoints) {
        if (registerEndpoints.length == 0) {
            throw new InvalidException("At least one server that required");
        }

        return Tasks.run(() -> {
            for (InetSocketAddress regEp : registerEndpoints) {
                synchronized (hold) {
                    if (!NQuery.of(hold).any(p -> eq(p.left, regEp))) {
                        BiTuple<InetSocketAddress, Nameserver, Integer> tuple = BiTuple.of(regEp, null, null);
                        hold.add(tuple);
                        tuple.middle = Remoting.create(Nameserver.class, RpcClientConfig.statefulMode(regEp, appName.hashCode()),
                                (ns, rc) -> {
                                    rc.onConnected.combine((s, e) -> {
                                        hold.setWeight(tuple, RandomList.DEFAULT_WEIGHT);
                                        reInject();
                                    });
                                    rc.onDisconnected.combine((s, e) -> {
                                        hold.setWeight(tuple, 0);
                                        reInject();
                                    });
                                    ns.<NEventArgs<Set<InetSocketAddress>>>attachEvent(Nameserver.EVENT_CLIENT_SYNC, (s, e) -> {
                                        log.info("sync server endpoints: {}", toJsonString(e.getValue()));
                                        if (e.getValue().isEmpty()) {
                                            return;
                                        }

                                        registerAsync(NQuery.of(e.getValue()).toArray());
                                    }, false);
                                });
                        tuple.right = sneakyInvoke(() -> tuple.middle.register(appName, registerEndpoints), DEFAULT_RETRY);
                    }
                }
            }
        });
    }

    public CompletableFuture<?> deregisterAsync() {
        return Tasks.run(() -> {
            for (BiTuple<InetSocketAddress, Nameserver, Integer> tuple : hold) {
                sneakyInvoke(() -> tuple.middle.deregister(), DEFAULT_RETRY);
            }
        });
    }

    public List<InetAddress> discover(@NonNull String appName) {
        return hold.next().middle.discover(appName);
    }
}
