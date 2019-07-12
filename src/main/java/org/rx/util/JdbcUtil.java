package org.rx.util;

import com.google.common.base.Strings;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.common.InvalidOperationException;
import org.rx.common.NQuery;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
public class JdbcUtil implements AutoCloseable {
    protected static final String MySql = "com.mysql.jdbc.Driver";
    @Getter
    private String                host;
    @Getter
    private int                   port;
    private String                database;
    @Getter
    @Setter
    private int                   connectTimeout;
    @Getter
    @Setter
    private int                   readWriteTimeout;
    protected String              user;
    protected String              password;
    private List<AutoCloseable>   holds;

    public synchronized List<AutoCloseable> getHolds() {
        if (holds == null) {
            holds = Collections.synchronizedList(new ArrayList<>());
        }
        return holds;
    }

    public JdbcUtil(String host, int port, String databaseName, String user, String password) {
        this.host = host;
        this.port = port;
        this.database = databaseName;
        this.user = user;
        this.password = password;
    }

    @SneakyThrows
    protected Connection createConnection() {
        Class.forName(MySql);
        String connStr = getConnectionString();
        try {
            return DriverManager.getConnection(connStr, user, password);
        } catch (SQLException e) {
            String msg = "url:" + connStr;
            throw new InvalidOperationException("数据库连接异常：" + msg, e);
        }
    }

    protected String getConnectionString() {
        String connStr = "jdbc:mysql://" + host + ":" + port;
        if (!Strings.isNullOrEmpty(database)) {
            connStr += "/" + database;
        }
        connStr += "?useUnicode=true&characterEncoding=utf-8";
        if (connectTimeout > 0) {
            connStr += "&connectTimeout=" + connectTimeout;
        }
        if (readWriteTimeout > 0) {
            connStr += "&socketTimeout=" + readWriteTimeout;
        }
        return connStr;
    }

    @SneakyThrows
    public void testConnect() {
        try (Connection conn = createConnection()) {
        }
    }

    public ResultSet executeQuery(String sql) {
        return executeQuery(sql, false);
    }

    @SneakyThrows
    public ResultSet executeQuery(String sql, boolean memoryResultSet) {
        Connection conn = createConnection();
        PreparedStatement statement = conn.prepareStatement(sql,
                memoryResultSet ? ResultSet.TYPE_SCROLL_INSENSITIVE : ResultSet.TYPE_FORWARD_ONLY);
        getHolds().add(statement);
        getHolds().add(conn);
        return statement.executeQuery();
    }

    @SneakyThrows
    public int execute(String sql) {
        try (Connection conn = createConnection(); PreparedStatement statement = conn.prepareStatement(sql)) {
            return statement.executeUpdate();
        }
    }

    public void close() {
        for (AutoCloseable closeable : NQuery.of(getHolds()).toList()) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.error(String.format("Closing %s error..", closeable), e);
            }
        }
    }
}
