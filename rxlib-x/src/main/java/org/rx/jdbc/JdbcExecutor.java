package org.rx.jdbc;

import com.alibaba.druid.pool.DruidDataSource;
import com.mysql.jdbc.MySQLConnection;
import com.mysql.jdbc.StatementImpl;
import com.mysql.jdbc.exceptions.jdbc4.MySQLQueryInterruptedException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import com.zaxxer.hikari.pool.ProxyStatement;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.$;
import org.rx.bean.Tuple;
import org.rx.core.Delegate;
import org.rx.core.StringBuilder;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.io.MemoryStream;
import org.rx.util.function.BiFunc;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.sql.*;
import java.util.List;

import static org.rx.core.Extends.*;
import static org.rx.core.Sys.*;

@Slf4j
public class JdbcExecutor extends Disposable implements EventPublisher<JdbcExecutor>, JdbcExecutable {
    @RequiredArgsConstructor
    public static class DefaultDataSource extends SuperDataSource {
        @Getter
        final DataSourceConfig config;

        @Override
        public Connection getConnection() throws SQLException {
            return getConnection(config.username, config.password);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(config.jdbcUrl, username, password);
        }
    }

    public static final String SPLIT_SYMBOL = ";";
    public static final String STRING_SYMBOL = "`";

    public static String buildMysqlConnectionString(String host, int port, String database, long connectTimeout, long readWriteTimeout) {
        StringBuilder connStr = new StringBuilder("jdbc:mysql://" + host + ":" + port);
        if (!Strings.isEmpty(database)) {
            connStr.append("/").append(database);
        }
        connStr.append("?useUnicode=true&characterEncoding=utf-8");
        if (connectTimeout > 0) {
            connStr.append("&connectTimeout=").append(String.valueOf(connectTimeout));
        }
        if (readWriteTimeout > 0) {
            connStr.append("&socketTimeout=").append(String.valueOf(readWriteTimeout));
        }
        return connStr.toString();
    }

    public static String buildMysqlConnectionString(InetSocketAddress endpoint, String database, long connectTimeout, long readWriteTimeout) {
        return buildMysqlConnectionString(endpoint.getHostString(), endpoint.getPort(), database, connectTimeout, readWriteTimeout);
    }

    public static void fillParams(PreparedStatement cmd, String rawSql, Object[] params) throws SQLException {
        if (Arrays.isEmpty(params)) {
            return;
        }

        for (int i = 0; i < params.length; i++) {
            int pi = i + 1;
            if (!tryAs(params[i], MemoryStream.class, p -> {
                p.setPosition(0);
                cmd.setBinaryStream(pi, p.getReader());
            })) {
                cmd.setObject(pi, params[i]);
            }
        }
    }

    @SneakyThrows
    public static long getLastInsertId(Statement statement) {
        try (ResultSet resultSet = statement.getGeneratedKeys()) {
            if (!resultSet.next()) {
//                throw new InvalidOperationException("Generate keys fail");
                return -1;
            }
            return resultSet.getLong(1);
        }
    }

    //HikariDataSource Enhancer Connection 冲突
    public static <T extends Statement> T behaviorClose(T cmd) {
        Class<? extends Statement> type = cmd instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (T) proxy(type, (m, p) -> {
            if (Reflects.isCloseMethod(m)) {
                tryClose(cmd);
                tryClose(cmd.getConnection());
                return null;
            }
            return p.fastInvoke(cmd);
        }, true);
    }

    @SneakyThrows
    public static ResultSet behaviorClose(ResultSet resultSet, Connection conn) {
//        resultSet.getStatement().closeOnCompletion(); //无效
        return new ResultSetProxyObject(resultSet, () -> tryClose(conn)); //cmd.getConnection() 是真实连接，需要关闭代理连接
    }

    public final Delegate<JdbcExecutor, TimeoutEventArgs> onExecuteTimeout = Delegate.create();
    @Getter
    final DataSourceConfig config;
    @Getter
    final DataSource dataSource;
    protected boolean closeDataSource;
    @Setter
    private boolean enableStreamingResults;
    @Setter
    private long executeTimeoutMillis;
    private boolean interruptTimeoutExecution;

    public JdbcExecutor(@NonNull String jdbcUrl, String username, String password) {
        this(new DataSourceConfig(jdbcUrl, username, password));
    }

    public JdbcExecutor(@NonNull DataSourceConfig config) {
        this.dataSource = createDataSource(this.config = config);
    }

    public JdbcExecutor(@NonNull DataSource dataSource) {
        this.config = JdbcUtil.getDataSourceConfig(dataSource);
        this.dataSource = dataSource;
    }

    @SneakyThrows
    @Override
    protected void freeObjects() {
        if (closeDataSource) {
            tryClose(dataSource);
        }
    }

    private DataSource createDataSource(DataSourceConfig config) {
        DriverClassFlag driverClassFlag = recognizeUrl(config.getJdbcUrl());
        JdbcConfig jdbcConfig = as(config, JdbcConfig.class);
        if (jdbcConfig == null) {
            return new DefaultDataSource(config);
        }

        try {
            closeDataSource = true;
            enableStreamingResults = jdbcConfig.isEnableStreamingResults();
            executeTimeoutMillis = jdbcConfig.getExecuteTimeoutMillis();
            interruptTimeoutExecution = jdbcConfig.isInterruptTimeoutExecution();
            ConnectionPoolKind poolKind = jdbcConfig.getPoolKind();
            if (poolKind == null) {
                poolKind = ConnectionPoolKind.HikariCP;
            }
            switch (poolKind) {
                case HikariCP:
                    HikariConfig conf = new HikariConfig();
                    conf.setDriverClassName(driverClassFlag.getDriverClassName());
                    conf.setJdbcUrl(jdbcConfig.getJdbcUrl());
                    conf.setUsername(jdbcConfig.getUsername());
                    conf.setPassword(jdbcConfig.getPassword());
                    conf.setMinimumIdle(jdbcConfig.getMinPoolSize());
                    conf.setMaximumPoolSize(jdbcConfig.getMaxPoolSize());
                    conf.setConnectionTimeout(jdbcConfig.getConnectionTimeoutMillis());
                    conf.setIdleTimeout(jdbcConfig.getIdleTimeoutMillis());
                    conf.setMaxLifetime(jdbcConfig.getMaxLifetimeMillis());
                    conf.setLeakDetectionThreshold(60000);
//                    conf.setScheduledExecutor();
                    switch (driverClassFlag) {
                        case MySQL:
                            //https://dev.mysql.com/doc/connector-j/5.1/en/connector-j-reference-configuration-properties.html
                            conf.addDataSourceProperty("useSSL", Boolean.FALSE);
                            conf.addDataSourceProperty("useCompression", Boolean.FALSE);
                            conf.addDataSourceProperty("useUnicode", Boolean.TRUE);
                            conf.addDataSourceProperty("characterEncoding", "utf-8");
                            int multiple = 10;
                            conf.addDataSourceProperty("cachePrepStmts", Boolean.TRUE);
                            conf.addDataSourceProperty("prepStmtCacheSize", 25 * multiple);
                            conf.addDataSourceProperty("prepStmtCacheSqlLimit", 256 * multiple);
                            conf.addDataSourceProperty("cacheResultSetMetadata", Boolean.TRUE);
                            conf.addDataSourceProperty("metadataCacheSize", 50 * multiple);
                            conf.addDataSourceProperty("useServerPrepStmts", Boolean.TRUE);
                            conf.addDataSourceProperty("useLocalSessionState", Boolean.TRUE);
                            conf.addDataSourceProperty("rewriteBatchedStatements", Boolean.TRUE);
                            conf.addDataSourceProperty("cacheServerConfiguration", Boolean.TRUE);
                            conf.addDataSourceProperty("elideSetAutoCommits", Boolean.TRUE);
                            conf.addDataSourceProperty("maintainTimeStats", Boolean.FALSE);

                            conf.addDataSourceProperty("generateSimpleParameterMetadata", Boolean.TRUE);
                            conf.addDataSourceProperty("netTimeoutForStreamingResults", 0);
                            //connectionAttributes
                            break;
                    }
                    if (jdbcConfig.getPoolName() != null) {
                        conf.setPoolName(jdbcConfig.getPoolName());
                    }
                    return new HikariDataSource(conf);
                case Druid:
                    DruidDataSource dataSource = new DruidDataSource();
                    dataSource.setDriverClassName(driverClassFlag.getDriverClassName());
                    dataSource.setUrl(jdbcConfig.getJdbcUrl());
                    dataSource.setUsername(jdbcConfig.getUsername());
                    dataSource.setPassword(jdbcConfig.getPassword());
                    dataSource.setMinIdle(jdbcConfig.getMinPoolSize());
                    dataSource.setInitialSize(dataSource.getMinIdle());
                    dataSource.setAsyncInit(true);
                    dataSource.setMaxActive(jdbcConfig.getMaxPoolSize());
                    dataSource.setMaxWait(jdbcConfig.getConnectionTimeoutMillis());
                    dataSource.setMinEvictableIdleTimeMillis(jdbcConfig.getIdleTimeoutMillis());
                    dataSource.setMaxEvictableIdleTimeMillis(jdbcConfig.getMaxLifetimeMillis());
                    if (jdbcConfig.getPoolName() != null) {
                        dataSource.setName(jdbcConfig.getPoolName());
                    }
                    return dataSource;
                default:
                    return new DefaultDataSource(config);
            }
        } catch (Exception e) {
            throw new InvalidException(config.toString(), e);
        }
    }

    @SneakyThrows
    private DriverClassFlag recognizeUrl(String jdbcUrl) {
        DriverClassFlag driverClassFlag = DriverClassFlag.recognize(jdbcUrl);
        Class.forName(driverClassFlag.getDriverClassName());
        return driverClassFlag;
    }

    public ConnectionPoolMXBean getPoolMXBean() {
        ConnectionPoolMXBean poolBean = new ConnectionPoolMXBean();
        if (tryAs(dataSource, HikariDataSource.class, p -> {
            poolBean.setName(p.getPoolName());
            HikariPoolMXBean mxBean = p.getHikariPoolMXBean();
            poolBean.setIdleConnections(mxBean.getIdleConnections());
            poolBean.setActiveConnections(mxBean.getActiveConnections());
            poolBean.setTotalConnections(mxBean.getTotalConnections());
            poolBean.setThreadsAwaitingConnection(mxBean.getThreadsAwaitingConnection());
        }) || tryAs(dataSource, DruidDataSource.class, p -> {
            poolBean.setName(p.getName());
            poolBean.setActiveConnections(p.getActiveCount());
            poolBean.setTotalConnections(p.getPoolingCount());
            poolBean.setIdleConnections(poolBean.getTotalConnections() - poolBean.getActiveConnections());
            poolBean.setThreadsAwaitingConnection(p.getWaitThreadCount());
        })) ;
        return poolBean;
    }

    @SneakyThrows
    protected Connection createConnection() {
        return dataSource.getConnection();
    }

//    @SneakyThrows
//    public PreparedStatement prepareStatement(String sql, int generatedKeys) {
//        Connection conn = createConnection();
//        return conn.prepareStatement(sql, generatedKeys);
////        return behaviorClose(conn.prepareStatement(sql, generatedKeys));
//    }

    @SneakyThrows
    public Tuple<ParameterMetaData, ResultSetMetaData> getMetaData(String sql) {
        try (Connection conn = createConnection();
             PreparedStatement cmd = conn.prepareStatement(sql)) {
            return Tuple.of(cmd.getParameterMetaData(), cmd.getMetaData());
        }
    }

    public boolean testConnect() {
        return ifNull(quietly(() -> {
            try (Connection conn = createConnection()) {
                return conn.isValid(5);
            }
        }), false);
    }

    @Override
    public ResultSet executeQuery(String sql, Object[] params) {
        return executeQuery(sql, params, executeTimeoutMillis);
    }

    @Override
    public ResultSet executeQuery(String sql, Object[] params, long executeTimeoutMillis) {
        return executeQuery(sql, params, 0, executeTimeoutMillis);
    }

    public ResultSet executeQuery(String sql, Object[] params, int cursorType) {
        return executeQuery(sql, params, cursorType, executeTimeoutMillis);
    }

    @SneakyThrows
    public ResultSet executeQuery(String sql, Object[] params, int cursorType, long executeTimeoutMillis) {
        int rsType = ResultSet.TYPE_FORWARD_ONLY;
        int rsConcur = ResultSet.CONCUR_READ_ONLY;
        int fetchSize = 0;
        switch (cursorType) {
            //mysql默认
            case ResultSet.TYPE_FORWARD_ONLY:
            case ResultSet.CONCUR_READ_ONLY:
                if (enableStreamingResults) {
                    fetchSize = Integer.MIN_VALUE;
                }
                break;
            case ResultSet.TYPE_SCROLL_INSENSITIVE:
                rsType = ResultSet.TYPE_SCROLL_INSENSITIVE;
                break;
            case ResultSet.CONCUR_UPDATABLE:
                rsConcur = ResultSet.CONCUR_UPDATABLE;
                break;
        }

        Connection conn = createConnection();
        PreparedStatement cmd = conn.prepareStatement(sql, rsType, rsConcur);
        if (fetchSize != 0) {
            cmd.setFetchSize(fetchSize);
        }
//        log.info("ResultSetType {} + {} + {}", cmd.getResultSetType(), cmd.getResultSetConcurrency(), cmd.getFetchSize());
        TimeoutFuture f = null;
        try {
            fillParams(cmd, sql, params);
//            log.info("sql {} [timeout={}]", cmd.toString(), executeTimeoutMillis);
            f = queryTimeout(cmd, executeTimeoutMillis, sql, params);
            return behaviorClose(cmd.executeQuery(), conn);
        } catch (Throwable e) {
            tryClose(conn);
            throw handleError(e, sql, params);
        } finally {
            if (f != null) {
                f.cancel();
            }
        }
    }

    @Override
    public <T> T executeQuery(String sql, Object[] params, BiFunc<ResultSet, T> func) {
        return executeQuery(sql, params, func, executeTimeoutMillis);
    }

    @Override
    @SneakyThrows
    public <T> T executeQuery(String sql, Object[] params, BiFunc<ResultSet, T> func, long executeTimeoutMillis) {
        try (Connection conn = createConnection(); PreparedStatement cmd = conn.prepareStatement(sql)) {
            fillParams(cmd, sql, params);
            TimeoutFuture f = queryTimeout(cmd, executeTimeoutMillis, sql, params);
            try {
                return func.invoke(cmd.executeQuery());
            } catch (Throwable e) {
                throw handleError(e, sql, params);
            } finally {
                if (f != null) {
                    f.cancel();
                }
            }
        }
    }

    @Override
    public int execute(String sql, Object[] params) {
        return execute(sql, params, executeTimeoutMillis);
    }

    @Override
    public int execute(String sql, Object[] params, long executeTimeoutMillis) {
        return execute(sql, params, executeTimeoutMillis, Statement.NO_GENERATED_KEYS, null);
    }

    @Override
    public int execute(String sql, Object[] params, int generatedKeys, $<Long> lastInsertId) {
        return execute(sql, params, executeTimeoutMillis, generatedKeys, lastInsertId);
    }

    @Override
    @SneakyThrows
    public int execute(String sql, Object[] params, long executeTimeoutMillis, int generatedKeys, $<Long> lastInsertId) {
        try (Connection conn = createConnection(); PreparedStatement cmd = conn.prepareStatement(sql, generatedKeys)) {
            fillParams(cmd, sql, params);
            TimeoutFuture f = queryTimeout(cmd, executeTimeoutMillis, sql, params);
            try {
                int rowsAffected = cmd.executeUpdate();
                if (generatedKeys == Statement.RETURN_GENERATED_KEYS && lastInsertId != null) {
                    lastInsertId.v = getLastInsertId(cmd);
                }
                return rowsAffected;
            } catch (Throwable e) {
                throw handleError(e, sql, params);
            } finally {
                if (f != null) {
                    f.cancel();
                }
            }
        }
    }

    @Override
    public int[] executeBatch(String sql, List<Object[]> batchParams) {
        return executeBatch(sql, batchParams, executeTimeoutMillis);
    }

    @Override
    @SneakyThrows
    public int[] executeBatch(String sql, @NonNull List<Object[]> batchParams, long executeTimeoutMillis) {
        try (Connection conn = createConnection(); PreparedStatement cmd = conn.prepareStatement(sql)) {
            TimeoutFuture f = queryTimeout(cmd, executeTimeoutMillis, sql, batchParams.toArray());
            try {
                conn.setAutoCommit(false);
                for (Object[] params : batchParams) {
                    fillParams(cmd, sql, params);
                    cmd.addBatch();
                }
                int[] rowsAffected = cmd.executeBatch();
                conn.commit();
                return rowsAffected;
            } catch (Throwable e) {
                throw handleError(e, sql, Linq.from(batchParams).firstOrDefault());
            } finally {
                if (f != null) {
                    f.cancel();
                }
            }
        }
    }

    TimeoutFuture queryTimeout(Statement cmd, long executeTimeoutMillis, String sql, Object[] params) {
        if (executeTimeoutMillis <= 0) {
            return null;
        }

        //cmd.isClosed() 有锁不能用, mysql cancel 不灵, mysqlStmt.getConnection() 有锁
        return Tasks.timer().setTimeout(() -> {
            if (interruptTimeoutExecution) {
                Statement realStmt = cmd;
                ProxyStatement as = as(realStmt, ProxyStatement.class);
                if (as != null) {
                    realStmt = Reflects.readField(realStmt, "delegate");
                }
                if (!tryAs(realStmt, StatementImpl.class, mysqlStmt -> {
                    MySQLConnection mysqlConn = Reflects.readField(mysqlStmt, "connection");
                    String killCmd = JdbcUtil.killCommand(mysqlConn);
                    log.info("[ExecCancel] {} -> {}", killCmd, sql);
                    try (Connection conn = createConnection(); Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate(killCmd);
                    } catch (MySQLQueryInterruptedException e) {
                        //ignore
                        log.info("KILL QUERY FAIL: {}", e.getMessage());
                    }
                })) {
                    log.info("[ExecCancel] {}", sql);
                    cmd.cancel();
                }
            } else {
                log.info("[ExecTimeout] {}", sql);
            }
            raiseEvent(onExecuteTimeout, new TimeoutEventArgs(executeTimeoutMillis, sql, params));
            return false;
        }, executeTimeoutMillis);
    }

    Throwable handleError(Throwable e, String sql, Object[] params) {
        tryAs(e, SQLSyntaxErrorException.class, p -> {
            String pJson = Strings.EMPTY;
            if (!Arrays.isEmpty(params)) {
                pJson = toJsonString(params);
            }
            log.error("SQLSyntax {}\t{}", sql, pJson);
        });
        return e;
    }

    @Override
    public ResultSet executeQuery(String sql) {
        return executeQuery(sql, executeTimeoutMillis);
    }

    @Override
    @SneakyThrows
    public ResultSet executeQuery(String sql, long executeTimeoutMillis) {
        Connection conn = createConnection();
        Statement cmd = conn.createStatement();
        TimeoutFuture f = queryTimeout(cmd, executeTimeoutMillis, sql, Arrays.EMPTY_OBJECT_ARRAY);
        try {
            return behaviorClose(conn.createStatement().executeQuery(sql), conn);
        } catch (Throwable e) {
            tryClose(conn);
            throw handleError(e, sql, null);
        } finally {
            if (f != null) {
                f.cancel();
            }
        }
    }

    @Override
    public <T> T executeQuery(String sql, BiFunc<ResultSet, T> func) {
        return executeQuery(sql, func, executeTimeoutMillis);
    }

    @Override
    @SneakyThrows
    public <T> T executeQuery(String sql, BiFunc<ResultSet, T> func, long executeTimeoutMillis) {
        try (Connection conn = createConnection(); Statement cmd = conn.createStatement()) {
            TimeoutFuture f = queryTimeout(cmd, executeTimeoutMillis, sql, Arrays.EMPTY_OBJECT_ARRAY);
            try {
                return func.invoke(cmd.executeQuery(sql));
            } catch (Throwable e) {
                throw handleError(e, sql, null);
            } finally {
                if (f != null) {
                    f.cancel();
                }
            }
        }
    }

    @Override
    public int execute(String sql) {
        return execute(sql, executeTimeoutMillis);
    }

    @Override
    public int execute(String sql, long executeTimeoutMillis) {
        return execute(sql, executeTimeoutMillis, Statement.NO_GENERATED_KEYS, null);
    }

    @Override
    public int execute(String sql, int generatedKeys, $<Long> lastInsertId) {
        return execute(sql, executeTimeoutMillis, generatedKeys, lastInsertId);
    }

    @Override
    @SneakyThrows
    public int execute(String sql, long executeTimeoutMillis, int generatedKeys, $<Long> lastInsertId) {
        try (Connection conn = createConnection(); Statement cmd = conn.createStatement()) {
            TimeoutFuture f = queryTimeout(cmd, executeTimeoutMillis, sql, null);
            try {
                int rowsAffected = cmd.executeUpdate(sql, generatedKeys);
                if (generatedKeys == Statement.RETURN_GENERATED_KEYS && lastInsertId != null) {
                    lastInsertId.v = getLastInsertId(cmd);
                }
                return rowsAffected;
            } catch (Throwable e) {
                throw handleError(e, sql, null);
            } finally {
                if (f != null) {
                    f.cancel();
                }
            }
        }
    }

    @Override
    public int[] executeBatch(List<String> batchSql) {
        return executeBatch(batchSql, executeTimeoutMillis);
    }

    @Override
    @SneakyThrows
    public int[] executeBatch(@NonNull List<String> batchSql, long executeTimeoutMillis) {
        if (batchSql.isEmpty()) {
            return Arrays.EMPTY_INT_ARRAY;
        }

        try (Connection conn = createConnection(); Statement cmd = conn.createStatement()) {
            TimeoutFuture f = queryTimeout(cmd, executeTimeoutMillis, String.join(SPLIT_SYMBOL, batchSql), null);
            try {
                conn.setAutoCommit(false);
                for (String sql : batchSql) {
                    cmd.addBatch(sql);
                }
                int[] rowsAffected = cmd.executeBatch();
                conn.commit();
                return rowsAffected;
            } catch (Throwable e) {
                throw handleError(e, batchSql.get(0), null);
            } finally {
                if (f != null) {
                    f.cancel();
                }
            }
        }
    }
}
