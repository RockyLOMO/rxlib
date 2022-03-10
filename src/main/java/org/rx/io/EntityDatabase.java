package org.rx.io;

import org.rx.bean.DataTable;

import java.io.Serializable;
import java.util.List;

public interface EntityDatabase extends AutoCloseable {
    EntityDatabase DEFAULT = new EntityDatabaseImpl("./rx");

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

    boolean isInTransaction();

    void begin();

    void begin(int transactionIsolation);

    void commit();

    void rollback();
}
