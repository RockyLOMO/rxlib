package org.rx.net.socks;

import lombok.NonNull;
import org.rx.io.KeyValueStore;
import org.rx.io.KeyValueStoreConfig;

import static org.rx.core.App.eq;

final class DbAuthenticator implements Authenticator {
    final KeyValueStore<String, SocksUser> store;

    public int size() {
        return store.size();
    }

    public DbAuthenticator(Integer apiPort) {
        KeyValueStoreConfig config = KeyValueStoreConfig.miniConfig("./data/socks");
        config.setWriteBehindDelayed(15000);
        if (apiPort != null) {
            config.setApiPort(apiPort);
            config.setApiReturnJson(true);
        }
        store = new KeyValueStore<>(config);

        String n = "rocky";
        store.computeIfAbsent(n, k -> {
            SocksUser r = new SocksUser(n);
            r.setPassword("202002");
            return r;
        });
    }

    @Override
    public SocksUser login(String username, String password) {
        SocksUser user = store.get(username);
        return user != null && eq(user.getPassword(), password) ? user : null;
    }

    public void save(@NonNull SocksUser user) {
        store.putBehind(user.getUsername(), user);
    }

    public void delete(@NonNull SocksUser user) {
        store.remove(user.getUsername());
    }
}
