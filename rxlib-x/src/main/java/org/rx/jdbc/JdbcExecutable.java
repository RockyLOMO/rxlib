package org.rx.jdbc;

import lombok.NonNull;
import org.rx.bean.$;
import org.rx.util.function.BiFunc;

import java.sql.ResultSet;
import java.util.List;

public interface JdbcExecutable {
    ResultSet executeQuery(String sql, Object[] params);

    ResultSet executeQuery(String sql, Object[] params, long executeTimeoutMillis);

    <T> T executeScalar(String sql, Object[] params);

    <T> T executeScalar(String sql, Object[] params, long executeTimeoutMillis);

    <T> T executeQuery(String sql, Object[] params, BiFunc<ResultSet, T> func);

    <T> T executeQuery(String sql, Object[] params, BiFunc<ResultSet, T> func, long executeTimeoutMillis);

    int execute(String sql, Object[] params);

    int execute(String sql, Object[] params, long executeTimeoutMillis);

    int execute(String sql, Object[] params, int generatedKeys, $<Long> lastInsertId);

    int execute(String sql, Object[] params, long executeTimeoutMillis, int generatedKeys, $<Long> lastInsertId);

    int[] executeBatch(String sql, List<Object[]> batchParams);

    int[] executeBatch(String sql, @NonNull List<Object[]> batchParams, long executeTimeoutMillis);

    ResultSet executeQuery(String sql);

    ResultSet executeQuery(String sql, long executeTimeoutMillis);

    <T> T executeScalar(String sql);

    <T> T executeScalar(String sql, long executeTimeoutMillis);

    <T> T executeQuery(String sql, BiFunc<ResultSet, T> func);

    <T> T executeQuery(String sql, BiFunc<ResultSet, T> func, long executeTimeoutMillis);

    int execute(String sql);

    int execute(String sql, long executeTimeoutMillis);

    int execute(String sql, int generatedKeys, $<Long> lastInsertId);

    int execute(String sql, long executeTimeoutMillis, int generatedKeys, $<Long> lastInsertId);

    int[] executeBatch(List<String> batchSql);

    int[] executeBatch(@NonNull List<String> batchSql, long executeTimeoutMillis);
}
