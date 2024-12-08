package org.rx.net.socks;

import lombok.NonNull;
import org.rx.bean.DateTime;
import org.rx.core.Tasks;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Extends.eq;

public class DefaultSocksAuthenticator implements Authenticator {
    final Map<String, SocksUser> store = new ConcurrentHashMap<>();

    public DefaultSocksAuthenticator(List<SocksUser> initUsers) {
        for (SocksUser usr : initUsers) {
            store.put(usr.getUsername(), usr);
        }

        Tasks.scheduleDaily(() -> {
            resetIp();

            if (DateTime.now().getDay() == 1) {
                resetData();
            }
        }, "01:00:00");
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
        store.put(user.getUsername(), user);
    }

    public void resetIp() {
        for (SocksUser user : store.values()) {
            boolean changed = false;
            Map<InetAddress, SocksUser.LoginInfo> loginIps = user.getLoginIps();
            for (Map.Entry<InetAddress, SocksUser.LoginInfo> lEntry : loginIps.entrySet()) {
                DateTime latestTime = lEntry.getValue().latestTime;
                if (latestTime != null && latestTime.before(DateTime.now().addDays(-2))) {
                    loginIps.remove(lEntry.getKey());
                    changed = true;
                }
            }
            if (changed) {
                save(user);
            }
        }
    }

    public void resetData() {
        for (SocksUser usr : store.values()) {
            for (SocksUser.LoginInfo l : usr.getLoginIps().values()) {
                l.totalReadBytes.set(0);
                l.totalWriteBytes.set(0);
            }
            usr.lastResetTime = DateTime.now();
        }
    }
}
