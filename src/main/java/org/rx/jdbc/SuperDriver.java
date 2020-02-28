package org.rx.jdbc;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

@Slf4j
public abstract class SuperDriver implements Driver {
    public static void register(SuperDriver driver) {
        try {
            DriverManager.registerDriver(driver);
        } catch (SQLException e) {
            log.error("registerDriver", e);
        }
    }

    protected abstract String getUrlPrefix();

    @Override
    public boolean acceptsURL(String url) {
        if (Strings.isEmpty(url)) {
            return false;
        }
        return Strings.startsWithIgnoreCase(url, getUrlPrefix());
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getGlobal();
    }
}
