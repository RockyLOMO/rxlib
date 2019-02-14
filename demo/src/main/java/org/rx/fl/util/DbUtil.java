package org.rx.fl.util;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
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
import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.common.InvalidOperationException;
import org.rx.fl.repository.MyBatisBaseDao;
import org.rx.fl.repository.UserMapper;
import org.rx.util.SpringContextUtil;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.Date;

import static org.rx.common.Contract.require;

@Component
@Slf4j
public class DbUtil {
    public static final String IsDeleted_True = "Y", IsDeleted_False = "N";
    private static final String mapperScan = App.readSetting("app.mybatis.mapperScan");

    public static double toMoney(Long cent) {
        if (cent == null || cent == 0) {
            return 0;
        }
        return (double) cent / 100;
    }

    public static long toCent(String money) {
        if (App.isNullOrWhiteSpace(money)) {
            return 0;
        }
        return toCent(Double.valueOf(money));
    }

    public static long toCent(double money) {
        return ((Double) (money * 100)).longValue();
    }

    public static long longValue(Long num) {
        if (num == null) {
            return 0;
        }
        return num;
    }

    public <T> T selectById(MyBatisBaseDao mapper, String id) {
        require(mapper, id);

        T t = (T) mapper.selectByPrimaryKey(id);
        if (t == null) {
            throw new InvalidOperationException(String.format("%s.selectByPrimaryKey(%s) is null", mapper.getClass().getSimpleName(), id));
        }
        return t;
    }

    public <T> T save(T model) {
        return save(model, false);
    }

    @Transactional
    public <T> T save(T model, boolean forceInsert) {
        require(model);

        MyBatisBaseDao mapper = getMapper(model.getClass());
        boolean isInsert = false;
        String id = getValue(model, "id");
        if (id == null) {
            setValue(model, "id", id = App.newComb(false).toString());
            isInsert = true;
        }
        Date createTime = getValue(model, "createTime");
        if (createTime == null) {
            setValue(model, "createTime", createTime = DateTime.now());
        }
        Date modifyTime = getValue(model, "modifyTime");
        if (modifyTime == null) {
            setValue(model, "modifyTime", modifyTime = createTime);
        }
        String isDeleted = getValue(model, "isDeleted");
        if (isDeleted == null) {
            setValue(model, "isDeleted", isDeleted = "N");
        }
//        log.info("save model {}", toJsonString(model));
        int rows;
        if (forceInsert || isInsert) {
            rows = mapper.insertSelective(model);
        } else {
            rows = mapper.updateByPrimaryKeySelective(model);
        }
//        log.info("save rows {}", rows);
        return model;
    }

    private MyBatisBaseDao getMapper(Class modelType) {
        String className = String.format("%s.%sMapper", mapperScan, modelType.getSimpleName());
        return SpringContextUtil.getBean(App.loadClass(className, false));
    }

    @SneakyThrows
    private <T> T getValue(Object model, String name) {
        Field field = model.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(model);
    }

    @SneakyThrows
    private void setValue(Object model, String name, Object val) {
        Field field = model.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(model, App.changeType(val, field.getType()));
    }

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
        InputStream stream = DbUtil.class.getClassLoader().getResourceAsStream(url);
        ScriptRunner scriptRunner = new ScriptRunner(connection);
        scriptRunner.setLogWriter(null);

        try (Reader r = new InputStreamReader(stream)) {
            scriptRunner.runScript(r);
            connection.commit();
        }
    }
}
