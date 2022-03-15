package org.rx.io;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.CircuitBreakingException;
import org.rx.bean.$;
import org.rx.bean.DataTable;
import org.rx.bean.RandomList;
import org.rx.bean.Tuple;
import org.rx.core.NQuery;
import org.rx.exception.ExceptionHandler;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.nameserver.NameserverClient;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.util.function.BiAction;
import org.rx.util.function.BiFunc;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.rx.bean.$.$;
import static org.rx.core.Extends.asyncBreak;

@Slf4j
public class ShardingEntityDatabase implements EntityDatabase {
    public static final int DEFAULT_PORT = 3305;
    static final String APP_NAME = "EDB";
    final EntityDatabaseImpl local;
    final int rpcPort;
    final NameserverClient nsClient = new NameserverClient(APP_NAME);
    final RandomList<Tuple<InetSocketAddress, EntityDatabase>> shardingDbs = new RandomList<>();

    public ShardingEntityDatabase(String... registerEndpoints) {
        this(DEFAULT_PORT, registerEndpoints);
    }

    public ShardingEntityDatabase(int rpcPort, String... registerEndpoints) {
        this(DEFAULT_FILE_PATH, null, 0, rpcPort, registerEndpoints);
    }

    public ShardingEntityDatabase(String filePath, String timeRollingPattern, int maxConnections,
                                  int rpcPort, String... registerEndpoints) {
        shardingDbs.setSortFunc(p -> p.left.getHostString());
        local = new EntityDatabaseImpl(filePath, timeRollingPattern, maxConnections);
        this.rpcPort = rpcPort;

        Remoting.listen(local, rpcPort);
        nsClient.registerAsync(registerEndpoints).whenComplete((r, e) -> {
            if (e != null) {
                return;
            }
            shardingDbs.add(Tuple.of(new InetSocketAddress(Sockets.loopbackAddress(), rpcPort), local));
            shardingDbs.addAll(NQuery.of(nsClient.discoverAll(APP_NAME, true)).select(p -> {
                InetSocketAddress ep = new InetSocketAddress(p, rpcPort);
                return Tuple.of(ep, Remoting.create(EntityDatabase.class, RpcClientConfig.poolMode(ep, 2, local.maxConnections)));
            }).toList());
            log.info("{} init {} shardingDbs", APP_NAME, shardingDbs.size());
            try {
                nsClient.wait4Inject();
            } catch (TimeoutException ex) {
                ExceptionHandler.INSTANCE.log(ex);
            }
        }).join();

        nsClient.onAppAddressChanged.combine((s, e) -> {
            if (!e.getAppName().equals(APP_NAME)) {
                return;
            }
            InetSocketAddress ep = new InetSocketAddress(e.getAddress(), rpcPort);
            log.info("{} address registered: {} -> {} isUp={}", APP_NAME,
                    NQuery.of(shardingDbs).toJoinString(",", p -> p.left.toString()),
                    ep, e.isUp());
            synchronized (shardingDbs) {
                if (e.isUp()) {
                    if (!NQuery.of(shardingDbs).any(p -> p.left.equals(ep))) {
                        shardingDbs.add(Tuple.of(ep, Remoting.create(EntityDatabase.class, RpcClientConfig.poolMode(ep, 2, local.maxConnections))));
                    }
                } else {
                    shardingDbs.removeIf(p -> p.left.equals(ep));
                }
            }
        });
    }

    @Override
    public synchronized void close() {
        nsClient.close();
        shardingDbs.clear();
        local.close();
    }

    @SneakyThrows
    @Override
    public <T> void save(T entity) {
        EntityDatabaseImpl.SqlMeta meta = local.getMeta(entity.getClass());
        Object id = meta.primaryKey.getValue().left.get(entity);
        invokeSharding(p -> {
            p.save(entity);
            return null;
        }, id);
    }

    @SneakyThrows
    @Override
    public <T> void save(T entity, boolean doInsert) {
        EntityDatabaseImpl.SqlMeta meta = local.getMeta(entity.getClass());
        Object id = meta.primaryKey.getValue().left.get(entity);
        invokeSharding(p -> {
            p.save(entity, doInsert);
            return null;
        }, id);
    }

    @Override
    public <T> boolean deleteById(Class<T> entityType, Serializable id) {
        return invokeSharding(p -> p.deleteById(entityType, id), id);
    }

    @Override
    public <T> long delete(EntityQueryLambda<T> query) {
        AtomicLong rf = new AtomicLong();
        invokeAll(p -> rf.addAndGet(p.delete(query)));
        return rf.get();
    }

    @Override
    public <T> long count(EntityQueryLambda<T> query) {
        AtomicLong rf = new AtomicLong();
        invokeAll(p -> rf.addAndGet(p.count(query)));
        return rf.get();
    }

    @Override
    public <T> boolean exists(EntityQueryLambda<T> query) {
        AtomicBoolean rf = new AtomicBoolean();
        invokeAll(p -> {
            if (p.exists(query)) {
                rf.set(true);
                asyncBreak();
            }
        });
        return rf.get();
    }

    @Override
    public <T> boolean existsById(Class<T> entityType, Serializable id) {
        return invokeSharding(p -> p.existsById(entityType, id), id);
    }

    @Override
    public <T> T findById(Class<T> entityType, Serializable id) {
        return invokeSharding(p -> p.findById(entityType, id), id);
    }

    @Override
    public <T> T findOne(EntityQueryLambda<T> query) {
        $<T> h = $();
        invokeAll(p -> {
            h.v = p.findOne(query);
            if (h.v != null) {
                asyncBreak();
            }
        });
        return h.v;
    }

    @Override
    public <T> List<T> findBy(EntityQueryLambda<T> query) {
        List<T> r = new ArrayList<>();
        invokeAll(p -> r.addAll(p.findBy(query)));
        return EntityQueryLambda.sharding(r, query);
    }

    @Override
    public void compact() {
        invokeAll(EntityDatabase::compact);
    }

    @Override
    public <T> void dropMapping(Class<T> entityType) {
        invokeAll(p -> p.dropMapping(entityType));
    }

    @Override
    public void createMapping(Class<?>... entityTypes) {
        invokeAll(p -> p.createMapping(entityTypes));
    }

    @Override
    public String tableName(Class<?> entityType) {
        return local.tableName(entityType);
    }

    @Override
    public <T> DataTable executeQuery(String sql, Class<T> entityType) {
        List<DataTable> r = new Vector<>();
        invokeAllAsync(p -> r.add(p.executeQuery(sql, entityType)));
        return EntityDatabaseImpl.sharding(r, sql);
    }

    @Override
    public boolean isInTransaction() {
        return local.isInTransaction();
    }

    @Override
    public void begin() {
//        local.begin();
    }

    @Override
    public void begin(int transactionIsolation) {
//        local.begin(transactionIsolation);
    }

    @Override
    public void commit() {
//        local.commit();
    }

    @Override
    public void rollback() {
//        local.rollback();
    }

    @SneakyThrows
    <T> T invokeSharding(BiFunc<EntityDatabase, T> fn, Object shardingKey) {
        int len = shardingDbs.size();
        int i = Math.abs(shardingKey.hashCode()) % len;
        EntityDatabase db = shardingDbs.get(i).right;
//        log.info("{} route {}/{} -> {}", APP_NAME, i, len, db.getClass().getSimpleName());
        return fn.invoke(db);
    }

    @SneakyThrows
    void invokeAll(BiAction<EntityDatabase> fn) {
        for (Tuple<InetSocketAddress, EntityDatabase> tuple : shardingDbs) {
            try {
                fn.invoke(tuple.right);
            } catch (CircuitBreakingException e) {
                break;
            }
        }
    }

    void invokeAllAsync(BiAction<EntityDatabase> fn) {
        NQuery.of(shardingDbs, true).forEach(tuple -> {
            try {
                fn.invoke(tuple.right);
            } catch (Throwable e) {
                throw InvalidException.sneaky(e);
            }
        });
    }
}
