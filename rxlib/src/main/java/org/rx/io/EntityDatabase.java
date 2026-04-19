package org.rx.io;

import lombok.SneakyThrows;
import org.rx.bean.DataTable;
import org.rx.util.function.Action;
import org.rx.util.function.BiAction;
import org.rx.util.function.BiFunc;
import org.rx.util.function.Func;

import java.io.Serializable;
import java.sql.Connection;
import java.util.List;

public interface EntityDatabase extends AutoCloseable {
    EntityDatabase DEFAULT = new EntityDatabaseImpl();

    <T> void save(T entity);

    <T> void save(T entity, boolean doInsert);

    <T> boolean deleteById(Class<T> entityType, Serializable id);

    <T> long delete(EntityQueryLambda<T> query);

    <T> long count(EntityQueryLambda<T> query);

    <T> boolean exists(EntityQueryLambda<T> query);

    <T> boolean existsById(Class<T> entityType, Serializable id);

    <T> T findById(Class<T> entityType, Serializable id);

    <T> T findOne(EntityQueryLambda<T> query);

    <T> List<T> findBy(EntityQueryLambda<T> query);

    void compact();

    <T> void truncateMapping(Class<T> entityType);

    <T> void dropMapping(Class<T> entityType);

    void createMapping(Class<?>... entityTypes);

    String tableName(Class<?> entityType);

    <T> DataTable executeQuery(String sql, Class<T> entityType);

    int executeUpdate(String sql);

    // 内部低层组件复用连接池、事务和慢 SQL 记录，避免重复造 JDBC 生命周期。
    default void withConnection(BiAction<Connection> fn) {
        throw new UnsupportedOperationException("Low-level JDBC connection is not supported");
    }

    default <T> T withConnection(BiFunc<Connection, T> fn) {
        throw new UnsupportedOperationException("Low-level JDBC connection is not supported");
    }

    default int[] executeBatch(String sql, List<List<Object>> argsList) {
        throw new UnsupportedOperationException("Batch JDBC execution is not supported");
    }

    @SneakyThrows
    default void transInvoke(int transactionIsolation, Action fn) {
        boolean doCommit = false;
        begin(transactionIsolation);
        try {
            fn.invoke();
            doCommit = true;
        } finally {
            if (doCommit) {
                commit();
            } else {
                rollback();
            }
        }
    }

    @SneakyThrows
    default <T> T transInvoke(int transactionIsolation, Func<T> fn) {
        boolean doCommit = false;
        begin(transactionIsolation);
        try {
            T r = fn.invoke();
            doCommit = true;
            return r;
        } finally {
            if (doCommit) {
                commit();
            } else {
                rollback();
            }
        }
    }

    boolean isInTransaction();

    void begin();

    void begin(int transactionIsolation);

    void commit();

    void rollback();
}
