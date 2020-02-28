package org.rx.jdbc;

import lombok.Getter;
import org.rx.beans.NEnum;
import org.rx.core.Arrays;
import org.rx.core.InvalidOperationException;
import org.rx.core.NQuery;
import org.rx.core.Strings;

import java.util.Collections;
import java.util.List;

@Getter
public enum DriverClassFlag implements NEnum {
    MySQL(1, "com.mysql.jdbc.Driver", "jdbc:mysql:"),
    PostgreSQL(2, "org.postgresql.Driver", "jdbc:postgresql:"),
    Oracle(3, "oracle.jdbc.driver.OracleDriver", "jdbc:oracle:"),
    SQLServer(4, "com.microsoft.sqlserver.jdbc.SQLServerDriver", "jdbc:sqlserver:", "jdbc:microsoft:sqlserver:"),
    MongoDB(6, "org.rx.jdbc.mongodb.MongoDriver", "jdbc:mongodb:");

    private int value;
    private String driverClassName;
    private List<String> urlPrefixes;

    DriverClassFlag(int value, String driverClassName, String... urlPrefixes) {
        this.value = value;
        this.driverClassName = driverClassName;
        this.urlPrefixes = Collections.unmodifiableList(Arrays.toList(urlPrefixes));
    }

    public static DriverClassFlag recognize(String jdbcUrl) {
        DriverClassFlag flag = NQuery.of(DriverClassFlag.values()).firstOrDefault(p -> NQuery.of(p.urlPrefixes).any(x -> Strings.startsWithIgnoreCase(jdbcUrl, x)));
        if (flag == null) {
            throw new InvalidOperationException("Recognize url %s fail", jdbcUrl);
        }
        return flag;
    }
}
