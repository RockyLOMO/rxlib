package org.rx.io;

import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.$;
import org.rx.bean.DataTable;
import org.rx.bean.RandomList;
import org.rx.bean.Tuple;
import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.exception.TraceHandler;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.rx.bean.$.$;
import static org.rx.core.Extends.circuitContinue;
import static org.rx.core.Extends.eachQuietly;

@Slf4j
public class ShardingEntityDatabase implements EntityDatabase {
    public static final int DEFAULT_PORT = 3305;
    static final String APP_NAME = "EDB";
    final EntityDatabaseImpl local;
    final int rpcPort;
    final NameserverClient nsClient = new NameserverClient(APP_NAME);
    final RandomList<Tuple<InetSocketAddress, EntityDatabase>> nodes = new RandomList<>();
    @Setter
    boolean enableAsync = true;
    @Setter
    boolean dynamicNodes = true;

    public ShardingEntityDatabase(String... registerEndpoints) {
        this(DEFAULT_PORT, registerEndpoints);
    }

    public ShardingEntityDatabase(int rpcPort, String... registerEndpoints) {
        this(DEFAULT_FILE_PATH, null, 0, rpcPort, registerEndpoints);
    }

    public ShardingEntityDatabase(String filePath, String timeRollingPattern, int maxConnections,
                                  int rpcPort, String... registerEndpoints) {
        nodes.setSortFunc(p -> p.left.getHostString());
        local = new EntityDatabaseImpl(filePath, timeRollingPattern, maxConnections);
        this.rpcPort = rpcPort;

        Remoting.register(local, rpcPort, false);
        nsClient.registerAsync(registerEndpoints).whenComplete((r, e) -> {
            if (e != null) {
                return;
            }
            nodes.add(Tuple.of(new InetSocketAddress(Sockets.getLoopbackAddress(), rpcPort), local));
            nodes.addAll(Linq.from(nsClient.discoverAll(APP_NAME, true)).select(p -> {
                InetSocketAddress ep = new InetSocketAddress(p, rpcPort);
                return Tuple.of(ep, Remoting.createFacade(EntityDatabase.class, RpcClientConfig.poolMode(ep, 2, local.maxConnections)));
            }).toList());
            log.info("{} init {} sharding nodes", APP_NAME, nodes.size());
            try {
                nsClient.waitInject();
            } catch (TimeoutException ex) {
                TraceHandler.INSTANCE.log(ex);
            }
        }).join();

        nsClient.onAppAddressChanged.combine((s, e) -> {
            if (!Strings.hashEquals(APP_NAME, e.getAppName())) {
                return;
            }
            InetSocketAddress ep = new InetSocketAddress(e.getAddress(), rpcPort);
            log.info("{} address registered: {} -> {} isUp={}", APP_NAME,
                    Linq.from(nodes).toJoinString(",", p -> p.left.toString()),
                    ep, e.isUp());
            synchronized (nodes) {
                if (e.isUp()) {
                    if (!Linq.from(nodes).any(p -> p.left.equals(ep))) {
                        nodes.add(Tuple.of(ep, Remoting.createFacade(EntityDatabase.class, RpcClientConfig.poolMode(ep, 2, local.maxConnections))));
                    }
                } else {
                    nodes.removeIf(p -> p.left.equals(ep));
                }
            }
        });
    }

    @Override
    public synchronized void close() {
        nsClient.close();
        nodes.clear();
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
        if (dynamicNodes) {
            AtomicBoolean rf = new AtomicBoolean();
            invokeAll(p -> {
                if (p.deleteById(entityType, id)) {
                    rf.set(true);
                    circuitContinue(false);
                }
            });
            return rf.get();
        }
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
                circuitContinue(false);
            }
        });
        return rf.get();
    }

    @Override
    public <T> boolean existsById(Class<T> entityType, Serializable id) {
        if (dynamicNodes) {
            AtomicBoolean rf = new AtomicBoolean();
            invokeAll(p -> {
                if (p.existsById(entityType, id)) {
                    rf.set(true);
                    circuitContinue(false);
                }
            });
            return rf.get();
        }
        return invokeSharding(p -> p.existsById(entityType, id), id);
    }

    @Override
    public <T> T findById(Class<T> entityType, Serializable id) {
        if (dynamicNodes) {
            $<T> rf = $();
            invokeAll(p -> {
                rf.v = p.findById(entityType, id);
                if (rf.v != null) {
                    circuitContinue(false);
                }
            });
            return rf.v;
        }
        return invokeSharding(p -> p.findById(entityType, id), id);
    }

    @Override
    public <T> T findOne(EntityQueryLambda<T> query) {
        $<T> rf = $();
        invokeAll(p -> {
            rf.v = p.findOne(query);
            if (rf.v != null) {
                circuitContinue(false);
            }
        });
        return rf.v;
    }

    @Override
    public <T> List<T> findBy(EntityQueryLambda<T> query) {
        List<T> rf = enableAsync ? new Vector<>(nodes.size()) : new ArrayList<>(nodes.size());
        invokeAll(p -> rf.addAll(p.findBy(query)));
        return EntityQueryLambda.sharding(rf, query);
    }

    @Override
    public void compact() {
        invokeAll(EntityDatabase::compact);
    }

    @Override
    public <T> void truncateMapping(Class<T> entityType) {
        invokeAll(p -> p.truncateMapping(entityType));
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
        if (Strings.startsWithIgnoreCase(sql, "EXPLAIN")) {
            return local.executeQuery(sql, entityType);
        }

        List<DataTable> rf = enableAsync ? new Vector<>(nodes.size()) : new ArrayList<>(nodes.size());
        invokeAll(p -> rf.add(p.executeQuery(sql, entityType)));
        return EntityDatabaseImpl.sharding(rf, sql);
    }

    @Override
    public int executeUpdate(String sql) {
        AtomicInteger rf = new AtomicInteger();
        invokeAll(p -> rf.addAndGet(p.executeUpdate(sql)));
        return rf.get();
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
        int len = nodes.size();
        int i = Math.abs(shardingKey.hashCode()) % len;
        EntityDatabase db = nodes.get(i).right;
//        log.info("{} route {}/{} -> {}", APP_NAME, i, len, db.getClass().getSimpleName());
        return fn.invoke(db);
    }

    @SneakyThrows
    void invokeAll(BiAction<EntityDatabase> fn) {
        if (enableAsync) {
            Linq.from(nodes, true).forEach(tuple -> fn.accept(tuple.right));
            return;
        }

        eachQuietly(nodes, tuple -> fn.invoke(tuple.right));
    }
}
