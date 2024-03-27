package org.rx.jdbc;

import org.rx.core.Disposable;

import java.sql.SQLException;
import java.sql.Wrapper;

public abstract class SuperJdbc extends Disposable implements Wrapper {
    public static final String CATALOG = "def";

    @Override
    protected void freeObjects() {
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        try {
            return iface.cast(this);
        } catch (ClassCastException e) {
            throw new SQLException("Unable to unwrap to " + iface);
        }
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
