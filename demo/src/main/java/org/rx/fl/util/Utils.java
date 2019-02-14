package org.rx.fl.util;

import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.rx.fl.repository.UserMapper;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.sql.Connection;

public final class Utils {
    /**
     * 用于检查Update，Delete等SQL语句是否产生了影响，没产生影响时将抛出异常
     *
     * @param rows 影响行数
     * @throws IllegalArgumentException 如果没有影响任何行
     */
    public static void checkEffective(int rows) {
        if (rows <= 0) throw new IllegalArgumentException();
    }

    public static void checkPositive(int value, String valname) {
        if (value <= 0) throw new IllegalArgumentException("参数" + valname + "必须是正数:" + value);
    }

    public static SqlSession createSqlSession(String driver, String url, String user, String password) {
        UnpooledDataSource dataSource = new UnpooledDataSource();
        dataSource.setDriver(driver);
        dataSource.setUrl(url);
        dataSource.setUsername(user);
        dataSource.setPassword(password);
        dataSource.setAutoCommit(false);
        return createSqlSession(new PooledDataSource(dataSource));
    }

    public static SqlSession createSqlSession(DataSource dataSource) {
        TransactionFactory transactionFactory = new JdbcTransactionFactory();
        Environment environment = new Environment("test", transactionFactory, dataSource);

        Configuration config = new Configuration();
        config.setCacheEnabled(false);
        config.addMapper(UserMapper.class);
        config.setEnvironment(environment);

        SqlSessionFactory sessionFactory = new DefaultSqlSessionFactory(config);
        return sessionFactory.openSession();
    }

    /**
     * 运行SQL脚本文件。
     *
     * @param connection 数据库连接
     * @param url        文件路径（ClassPath）
     * @throws Exception 如果出现异常
     */
    public static void executeScript(Connection connection, String url) throws Exception {
        InputStream stream = Utils.class.getClassLoader().getResourceAsStream(url);
        ScriptRunner scriptRunner = new ScriptRunner(connection);
        scriptRunner.setLogWriter(null);

        try (Reader r = new InputStreamReader(stream)) {
            scriptRunner.runScript(r);
            connection.commit();
        }
    }

    private Utils() {
    }
}
