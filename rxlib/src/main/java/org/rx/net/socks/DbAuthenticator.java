//package org.rx.net.socks;
//
//import lombok.NonNull;
//import org.apache.commons.collections4.CollectionUtils;
//import org.rx.bean.DateTime;
//import org.rx.core.Tasks;
//import org.rx.io.KeyValueStore;
//import org.rx.io.KeyValueStoreConfig;
//
//import java.net.InetAddress;
//import java.util.List;
//import java.util.Map;
//
//import static org.rx.core.Extends.eq;
//
//final class DbAuthenticator implements Authenticator {
//    final KeyValueStore<String, SocksUser> store;
//
//    public int size() {
//        return store.size();
//    }
//
//    public DbAuthenticator(List<SocksUser> initUsers, Integer apiPort) {
//        KeyValueStoreConfig config = KeyValueStoreConfig.newConfig(String.class, SocksUser.class);
//        if (apiPort != null) {
//            config.setApiPort(apiPort);
//            config.setApiReturnJson(true);
//        }
//        store = new KeyValueStore<>(config);
//
//        if (!CollectionUtils.isEmpty(initUsers)) {
//            for (SocksUser usr : initUsers) {
//                SocksUser user = store.computeIfAbsent(usr.getUsername(), SocksUser::new);
//                user.setPassword(usr.getPassword());
//                user.setMaxIpCount(usr.getMaxIpCount());
//                store.fastPut(user.getUsername(), user);
//            }
//        }
//
//        Tasks.scheduleDaily(() -> {
//            resetIp();
//
//            if (DateTime.now().getDay() == 1) {
//                resetData();
//            }
//        }, "01:00:00");
//    }
//
//    @Override
//    public SocksUser login(String username, String password) {
//        SocksUser user = store.get(username);
//        if (user == null) {
//            return null;
//        }
//        if (!eq(user.getPassword(), password)) {
//            return null;
//        }
//        return user;
//    }
//
//    public void save(@NonNull SocksUser user) {
//        store.fastPut(user.getUsername(), user);
//    }
//
//    public void delete(@NonNull SocksUser user) {
//        store.fastRemove(user.getUsername());
//    }
//
//    public void resetIp() {
//        for (SocksUser user : store.values()) {
//            boolean changed = false;
//            Map<InetAddress, SocksUser.LoginInfo> loginIps = user.getLoginIps();
//            for (Map.Entry<InetAddress, SocksUser.LoginInfo> lEntry : loginIps.entrySet()) {
//                DateTime latestTime = lEntry.getValue().latestTime;
//                if (latestTime != null && latestTime.before(DateTime.now().addDays(-2))) {
//                    loginIps.remove(lEntry.getKey());
//                    changed = true;
//                }
//            }
//            if (changed) {
//                save(user);
//            }
//        }
//    }
//
//    public void resetData() {
//        for (SocksUser usr : store.values()) {
//            for (SocksUser.LoginInfo l : usr.getLoginIps().values()) {
//                l.totalReadBytes.set(0);
//                l.totalWriteBytes.set(0);
//            }
//            usr.lastResetTime = DateTime.now();
//        }
//    }
//}
