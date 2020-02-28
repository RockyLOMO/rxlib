package org.rx.jdbc;

import org.rx.core.Disposable;

import java.sql.SQLException;
import java.sql.Wrapper;

public abstract class SuperJdbc extends Disposable implements Wrapper {
    public static final String Catalog = "def";

    @Override
    protected void freeObjects() {
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (!isWrapperFor(iface)) {
            throw new SQLException("Type [" + getClass().getName() + "] cannot be unwrapped as [" + iface.getName() + "]");
        }
        return (T) this;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }
}
