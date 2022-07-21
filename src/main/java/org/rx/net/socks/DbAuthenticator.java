package org.rx.net.socks;

import lombok.NonNull;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.DateTime;
import org.rx.io.KeyValueStore;
import org.rx.io.KeyValueStoreConfig;

import java.util.List;

import static org.rx.core.Extends.eq;

final class DbAuthenticator implements Authenticator {
    final KeyValueStore<String, SocksUser> store;
    final DateTime startTime = DateTime.utcNow();

    public int size() {
        return store.size();
    }

    public DbAuthenticator(List<SocksUser> initUsers, Integer apiPort) {
        KeyValueStoreConfig config = KeyValueStoreConfig.defaultConfig("./data/socks");
        config.setWriteBehindDelayed(15000);
        if (apiPort != null) {
            config.setApiPort(apiPort);
            config.setApiReturnJson(true);
        }
        store = new KeyValueStore<>(config);

        if (!CollectionUtils.isEmpty(initUsers)) {
            for (SocksUser usr : initUsers) {
                SocksUser user = store.computeIfAbsent(usr.getUsername(), SocksUser::new);
                user.setPassword(usr.getPassword());
                user.setMaxIpCount(usr.getMaxIpCount());
                store.putBehind(user.getUsername(), user);
            }
        }
    }

    @Override
    public SocksUser login(String username, String password) {
        SocksUser user = store.get(username);
        if (user == null) {
            return null;
        }
        if (!eq(user.getPassword(), password)) {
            return null;
        }
        if (user.getLatestLoginTime() == null || user.getLatestLoginTime().before(startTime)) {
            user.getLoginIps().clear();
        }
        user.setLatestLoginTime(DateTime.utcNow());
        save(user);
        return user;
    }

    public void save(@NonNull SocksUser user) {
        store.putBehind(user.getUsername(), user);
    }

    public void delete(@NonNull SocksUser user) {
        store.remove(user.getUsername());
    }
}
