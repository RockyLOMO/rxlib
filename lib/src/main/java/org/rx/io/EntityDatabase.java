package org.rx.io;

import lombok.SneakyThrows;
import org.rx.bean.DataTable;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.io.Serializable;
import java.util.List;

public interface EntityDatabase extends AutoCloseable {
    String DEFAULT_FILE_PATH = "./rx";
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

    <T> void dropMapping(Class<T> entityType);

    void createMapping(Class<?>... entityTypes);

    String tableName(Class<?> entityType);

    <T> DataTable executeQuery(String sql, Class<T> entityType);

    int executeUpdate(String sql);

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
