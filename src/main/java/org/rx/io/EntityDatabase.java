package org.rx.io;

import org.rx.bean.DataTable;

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

    boolean isInTransaction();

    void begin();

    void begin(int transactionIsolation);

    void commit();

    void rollback();
}
