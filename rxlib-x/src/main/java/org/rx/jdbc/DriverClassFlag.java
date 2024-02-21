package org.rx.jdbc;

import lombok.Getter;
import org.rx.bean.NEnum;
import org.rx.core.Arrays;
import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;

import java.util.Collections;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 *
 * @author wangxiaoming
 * Date: 2019/9/2
 */
@Getter
public enum DriverClassFlag implements NEnum<DriverClassFlag> {
    MySQL(1, "com.mysql.jdbc.Driver", "jdbc:mysql:"),
    PostgreSQL(2, "org.postgresql.Driver", "jdbc:postgresql:"),
    Oracle(3, "oracle.jdbc.driver.OracleDriver", "jdbc:oracle:"),
    SQLServer(4, "com.microsoft.sqlserver.jdbc.SQLServerDriver", "jdbc:sqlserver:", "jdbc:microsoft:sqlserver:"),
    H2(5, "org.h2.Driver", "jdbc:h2:"),
    MongoDB(6, "com.xdbc.jdbc.mongodb.MongoDriver", "jdbc:mongodb:");

    private final int value;
    private final String driverClassName;
    private final List<String> urlPrefixes;

    DriverClassFlag(int value, String driverClassName, String... urlPrefixes) {
        this.value = value;
        this.driverClassName = driverClassName;
        this.urlPrefixes = Collections.unmodifiableList(Arrays.toList(urlPrefixes));
    }

    public static DriverClassFlag recognize(String jdbcUrl) {
        DriverClassFlag flag = Linq.from(DriverClassFlag.values()).firstOrDefault(p -> Linq.from(p.urlPrefixes).any(x -> Strings.startsWithIgnoreCase(jdbcUrl, x)));
        if (flag == null) {
            throw new InvalidException("Recognize url {} fail", jdbcUrl);
        }
        return flag;
    }
}
