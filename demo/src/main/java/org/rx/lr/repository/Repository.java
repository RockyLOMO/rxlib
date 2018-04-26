package org.rx.lr.repository;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;
import com.db4o.config.Configuration;
import lombok.SneakyThrows;

public abstract class Repository {
    private ObjectContainer db;

    @SneakyThrows
    public synchronized ObjectContainer getDb() {
        if (db == null) {
            Configuration config = Db4o.newConfiguration();
            config.setBlobPath("D:\rx.dat");
            db = Db4o.openFile(config, "rx");
        }
        return db;
    }
}
