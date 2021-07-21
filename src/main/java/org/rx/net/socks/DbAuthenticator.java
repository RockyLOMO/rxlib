package org.rx.net.socks;

import lombok.NonNull;
import org.rx.io.KeyValueStore;
import org.rx.io.KeyValueStoreConfig;

import static org.rx.core.App.eq;

class DbAuthenticator implements Authenticator {
    final KeyValueStore<String, SocksUser> store = new KeyValueStore<>(KeyValueStoreConfig.miniConfig("./data/socks"));

    @Override
    public boolean auth(String username, String password) {
        SocksUser user = store.get(username);
        return user != null && eq(user.getPassword(), password);
    }

    public void save(@NonNull SocksUser user) {
        store.putBehind(user.getUsername(), user);
    }
}
