package org.rx.net.socks;

import lombok.NonNull;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.io.KeyValueStore;
import org.rx.io.KeyValueStoreConfig;

import java.util.List;

import static org.rx.core.Extends.eq;

final class DbAuthenticator implements Authenticator {
    final KeyValueStore<String, SocksUser> store;

    public int size() {
        return store.size();
    }

    public DbAuthenticator(List<SocksUser> initUsers, Integer apiPort) {
        KeyValueStoreConfig config = KeyValueStoreConfig.newConfig(String.class, SocksUser.class);
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
                store.fastPut(user.getUsername(), user);
            }
        }

//        Tasks.scheduleDaily(() -> {
//            if (DateTime.now().getDay() != 1) {
//                return;
//            }
//            resetData();
//        }, "00:01:00");
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
        return user;
    }

    public void save(@NonNull SocksUser user) {
        store.fastPut(user.getUsername(), user);
    }

    public void delete(@NonNull SocksUser user) {
        store.remove(user.getUsername());
    }

    public void resetIp() {

    }

    public void resetData() {
//        DateTime firstDay = DateTime.valueOf(DateTime.now().toString("yyyy-MM-01 00:00:00"));
//        if (user.lastResetTime == null || user.lastResetTime.before(firstDay)) {
//            user.getTotalReadBytes().set(0);
//            user.getTotalWriteBytes().set(0);
//        }
//        user.lastResetTime = DateTime.now();
    }
}
