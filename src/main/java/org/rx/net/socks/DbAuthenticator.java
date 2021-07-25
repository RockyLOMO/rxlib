package org.rx.net.socks;

import lombok.NonNull;
import org.rx.io.KeyValueStore;
import org.rx.io.KeyValueStoreConfig;

import static org.rx.core.App.eq;

class DbAuthenticator implements Authenticator {
    final KeyValueStore<String, SocksUser> store;

    public DbAuthenticator(Integer apiPort) {
        KeyValueStoreConfig config = KeyValueStoreConfig.miniConfig("./data/socks");
        config.setWriteBehindDelayed(15000);
        if (apiPort != null) {
            config.setApiPort(8000);
            config.setApiReturnJson(true);
        }
        store = new KeyValueStore<>(config);
    }

    @Override
    public SocksUser login(String username, String password) {
        SocksUser user = store.get(username);
        return user != null && eq(user.getPassword(), password) ? user : null;
    }

    public void save(@NonNull SocksUser user) {
        store.putBehind(user.getUsername(), user);
    }
}
