package org.rx.jdbc;

import com.alibaba.druid.pool.DruidDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import org.rx.bean.$;
import org.rx.core.StringBuilder;
import org.rx.core.*;
import org.rx.util.function.BiFunc;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.sql.*;
import java.util.List;

import static org.rx.core.Contract.*;

@Slf4j
public class JdbcExecutor extends Disposable {
    public static String buildMysqlConnectionString(String host, int port, String database, int connectTimeout, int readWriteTimeout) {
        StringBuilder connStr = new StringBuilder("jdbc:mysql://" + host + ":" + port);
        if (!Strings.isNullOrEmpty(database)) {
            connStr.append("/" + database);
        }
        connStr.append("?useUnicode=true&characterEncoding=utf-8");
        if (connectTimeout > 0) {
            connStr.append("&connectTimeout=" + connectTimeout);
        }
        if (readWriteTimeout > 0) {
            connStr.append("&socketTimeout=" + readWriteTimeout);
        }
        return connStr.toString();
    }

    public static String buildMysqlConnectionString(InetSocketAddress endpoint, String database, int connectTimeout, int readWriteTimeout) {
        return buildMysqlConnectionString(endpoint.getHostString(), endpoint.getPort(), database, connectTimeout, readWriteTimeout);
    }

    @SneakyThrows
    public static long getLastInsertId(Statement statement) {
        try (ResultSet resultSet = statement.getGeneratedKeys()) {
            if (!resultSet.next()) {
//                throw new InvalidOperationException("GeneratedKeys Fail");
                return -1;
            }
            return resultSet.getLong(1);
        }
    }

    //HikariDataSource Enhancer Connection 冲突
    public static <T extends Statement> T behaviorClose(T cmd, Connection conn) {
        return (T) Enhancer.create(cmd instanceof PreparedStatement ? PreparedStatement.class : Statement.class, (MethodInterceptor) (o, method, objects, methodProxy) -> {
            if (Reflects.isCloseMethod(method)) {
                tryClose(cmd);
                tryClose(conn);
                return null;
            }
            return methodProxy.invoke(cmd, objects);
        });
    }

    @SneakyThrows
    public static ResultSet behaviorClose(ResultSet resultSet, Connection conn) {
        resultSet.getStatement().closeOnCompletion();
        return (ResultSet) Enhancer.create(ResultSet.class, (MethodInterceptor) (o, method, objects, methodProxy) -> {
            if (Reflects.isCloseMethod(method)) {
                tryClose(resultSet);
                tryClose(conn);
                return null;
            }
            return methodProxy.invoke(resultSet, objects);
        });
    }

    @Getter
    private String jdbcUrl;
    @Getter
    protected String user;
    @Getter
    protected String password;
    private DataSource dataSource;
    private boolean closeDataSource;

    public JdbcExecutor(String jdbcUrl, String user, String password) {
        require(jdbcUrl);
        recognizeUrl(jdbcUrl);

        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    public JdbcExecutor(JdbcConfig jdbcConfig) {
        require(jdbcConfig);

        this.dataSource = createDataSource(jdbcConfig);
    }

    public JdbcExecutor(DataSource dataSource) {
        require(dataSource);

        this.dataSource = dataSource;
    }

    @SneakyThrows
    @Override
    protected void freeObjects() {
        if (closeDataSource) {
            tryClose(dataSource);
        }
    }

    @SneakyThrows
    private DriverClassFlag recognizeUrl(String jdbcUrl) {
        DriverClassFlag driverClassFlag = DriverClassFlag.recognize(jdbcUrl);
        Class.forName(driverClassFlag.getDriverClassName());
        return driverClassFlag;
    }

    protected JdbcConnectionBean getDataSourceBean() {
        JdbcConnectionBean poolBean = new JdbcConnectionBean();
        if (dataSource == null) {
            return poolBean;
        }
        if (!tryAs(dataSource, DruidDataSource.class, p -> {
            poolBean.setActiveConnections(p.getActiveCount());
            poolBean.setTotalConnections(p.getPoolingCount());
            poolBean.setIdleConnections(poolBean.getTotalConnections() - poolBean.getActiveConnections());
            poolBean.setThreadsAwaitingConnection(p.getWaitThreadCount());
        })) {
            HikariDataSource hikari = (HikariDataSource) dataSource;
            HikariPoolMXBean mxBean = hikari.getHikariPoolMXBean();
            poolBean.setIdleConnections(mxBean.getIdleConnections());
            poolBean.setActiveConnections(mxBean.getActiveConnections());
            poolBean.setTotalConnections(mxBean.getTotalConnections());
            poolBean.setThreadsAwaitingConnection(mxBean.getThreadsAwaitingConnection());
        }
        return poolBean;
    }

    private DataSource createDataSource(JdbcConfig jdbcConfig) {
        DriverClassFlag driverClassFlag = recognizeUrl(jdbcConfig.getUrl());
        try {
            closeDataSource = true;
            if (eq(jdbcConfig.getPool(), JdbcConnectionPool.Druid)) {
                DruidDataSource dataSource = new DruidDataSource();
                dataSource.setDriverClassName(driverClassFlag.getDriverClassName());
                dataSource.setUrl(jdbcUrl = jdbcConfig.getUrl());
                dataSource.setUsername(user = jdbcConfig.getUsername());
                dataSource.setPassword(password = jdbcConfig.getPassword());
                dataSource.setMinIdle(jdbcConfig.getMinPoolSize());
                dataSource.setInitialSize(dataSource.getMinIdle());
                dataSource.setAsyncInit(true);
                dataSource.setMaxActive(jdbcConfig.getMaxPoolSize());
                dataSource.setMaxWait(jdbcConfig.getConnectionTimeoutMilliseconds());
                dataSource.setMinEvictableIdleTimeMillis(jdbcConfig.getIdleTimeoutMilliseconds());
                dataSource.setMaxEvictableIdleTimeMillis(jdbcConfig.getMaxLifetimeMilliseconds());
                return dataSource;
            }
            HikariConfig config = new HikariConfig();
            config.setDriverClassName(driverClassFlag.getDriverClassName());
            config.setJdbcUrl(jdbcUrl = jdbcConfig.getUrl());
            config.setUsername(user = jdbcConfig.getUsername());
            config.setPassword(password = jdbcConfig.getPassword());
            config.setMinimumIdle(jdbcConfig.getMinPoolSize());
            config.setMaximumPoolSize(jdbcConfig.getMaxPoolSize());
            config.setConnectionTimeout(jdbcConfig.getConnectionTimeoutMilliseconds());
            config.setIdleTimeout(jdbcConfig.getIdleTimeoutMilliseconds());
            config.setMaxLifetime(jdbcConfig.getMaxLifetimeMilliseconds());
            switch (driverClassFlag) {
                case MySQL:
                    //https://dev.mysql.com/doc/connector-j/5.1/en/connector-j-reference-configuration-properties.html
                    config.addDataSourceProperty("useSSL", Boolean.FALSE);
                    config.addDataSourceProperty("useCompression", Boolean.FALSE);
                    config.addDataSourceProperty("useUnicode", Boolean.TRUE);
                    config.addDataSourceProperty("characterEncoding", "utf-8");
                    int multiple = 10;
                    config.addDataSourceProperty("metadataCacheSize", 50 * multiple);
                    config.addDataSourceProperty("prepStmtCacheSize", 25 * multiple);
                    config.addDataSourceProperty("prepStmtCacheSqlLimit", 256 * multiple);
                    config.addDataSourceProperty("cachePrepStmts", Boolean.TRUE);
                    config.addDataSourceProperty("cacheResultSetMetadata", Boolean.TRUE);
                    config.addDataSourceProperty("cacheServerConfiguration", Boolean.TRUE);

                    config.addDataSourceProperty("useLocalSessionState", Boolean.TRUE);
                    config.addDataSourceProperty("elideSetAutoCommits", Boolean.TRUE);

                    config.addDataSourceProperty("maintainTimeStats", Boolean.FALSE);
                    config.addDataSourceProperty("rewriteBatchedStatements", Boolean.TRUE);
                    config.addDataSourceProperty("useServerPrepStmts", Boolean.TRUE);
                    config.addDataSourceProperty("generateSimpleParameterMetadata", Boolean.TRUE);
                    config.addDataSourceProperty("netTimeoutForStreamingResults", 0);
                    //connectionAttributes
                    break;
            }
            return new HikariDataSource(config);
        } catch (Exception e) {
            throw new InvalidOperationException(e, jdbcUrl);
        }
    }

    @SneakyThrows
    protected Connection createConnection() {
        if (dataSource != null) {
            return dataSource.getConnection();
        }
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    @SneakyThrows
    public PreparedStatement prepareStatement(String sql, int generatedKeys) {
        Connection conn = createConnection();
        return behaviorClose(conn.prepareStatement(sql, generatedKeys), conn);
    }

    public boolean testConnect() {
        return catchCall(() -> {
            try (Connection conn = createConnection()) {
                return conn.isValid(6);
            }
        });
    }

    @SneakyThrows
    public ResultSet executeQuery(String sql, Object[] params) {
        Connection conn = createConnection();
        PreparedStatement cmd = conn.prepareStatement(sql);
        fillParams(cmd, params);
        return behaviorClose(cmd.executeQuery(), conn);
    }

    @SneakyThrows
    public <T> T executeQuery(String sql, Object[] params, BiFunc<ResultSet, T> func) {
        try (Connection conn = createConnection(); PreparedStatement cmd = conn.prepareStatement(sql)) {
            fillParams(cmd, params);
            return func.invoke(cmd.executeQuery());
        }
    }

    public int execute(String sql, Object[] params) {
        return execute(sql, params, Statement.NO_GENERATED_KEYS, null);
    }

    @SneakyThrows
    public int execute(String sql, Object[] params, int generatedKeys, $<Long> lastInsertId) {
        try (Connection conn = createConnection(); PreparedStatement cmd = conn.prepareStatement(sql, generatedKeys)) {
            fillParams(cmd, params);
            int rowsAffected = cmd.executeUpdate();
            if (generatedKeys == Statement.RETURN_GENERATED_KEYS && lastInsertId != null) {
                lastInsertId.v = getLastInsertId(cmd);
            }
            return rowsAffected;
        }
    }

    @SneakyThrows
    public int[] executeBatch(String sql, List<Object[]> batchParams) {
        require(sql, batchParams);

        try (Connection conn = createConnection(); PreparedStatement cmd = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (Object[] params : batchParams) {
                fillParams(cmd, params);
                cmd.addBatch();
            }
            int[] rowsAffected = cmd.executeBatch();
            conn.commit();
            return rowsAffected;
        }
    }

    @SneakyThrows
    private void fillParams(PreparedStatement cmd, Object[] params) {
        if (Arrays.isEmpty(params)) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            cmd.setObject(i + 1, params[i]);
        }
    }

    @SneakyThrows
    public ResultSet executeQuery(String sql) {
        Connection conn = createConnection();
        return behaviorClose(conn.createStatement().executeQuery(sql), conn);
    }

    @SneakyThrows
    public <T> T executeQuery(String sql, BiFunc<ResultSet, T> func) {
        try (Connection conn = createConnection(); Statement cmd = conn.createStatement()) {
            return func.invoke(cmd.executeQuery(sql));
        }
    }

    public int execute(String sql) {
        return execute(sql, Statement.NO_GENERATED_KEYS, null);
    }

    @SneakyThrows
    public int execute(String sql, int generatedKeys, $<Long> lastInsertId) {
        try (Connection conn = createConnection(); Statement cmd = conn.createStatement()) {
            int rowsAffected = cmd.executeUpdate(sql, generatedKeys);
            if (generatedKeys == Statement.RETURN_GENERATED_KEYS && lastInsertId != null) {
                lastInsertId.v = getLastInsertId(cmd);
            }
            return rowsAffected;
        }
    }

    @SneakyThrows
    public int[] executeBatch(List<String> batchSql) {
        require(batchSql);
        try (Connection conn = createConnection(); Statement cmd = conn.createStatement()) {
            conn.setAutoCommit(false);
            for (String sql : batchSql) {
                cmd.addBatch(sql);
            }
            int[] rowsAffected = cmd.executeBatch();
            conn.commit();
            return rowsAffected;
        }
    }
}
