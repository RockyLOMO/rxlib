package org.rx.util.rss;

import lombok.Getter;
import org.rx.bean.DateTime;
import org.rx.core.Strings;
import org.rx.core.Tasks;
import org.rx.exception.InvalidException;
import org.rx.net.socks.AuthResult;
import org.rx.net.socks.Authenticator;
import org.rx.net.socks.SocksUser;
import org.rx.net.socks.TrafficLoginInfo;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Extends.eq;

public class RssAuthenticator implements Authenticator {
    @Getter
    private final Map<String, ShadowUser> shadowStore = new ConcurrentHashMap<>();
    @Getter
    private final Map<String, SocksUser> socksStore = new ConcurrentHashMap<>();
    @Getter
    private final String socksPassword;

    public RssAuthenticator(List<ShadowUser> shadowUsers, String socksPassword) {
        this.socksPassword = socksPassword;
        for (ShadowUser shadowUser : shadowUsers) {
            if (shadowUser == null) {
                continue;
            }
            validateShadowUser(shadowUser);
            String username = shadowUser.getUsername();
            shadowUser.setUsername(username);
            ShadowUser oldShadowUser = ((ConcurrentHashMap<String, ShadowUser>) shadowStore).putIfAbsent(username, shadowUser);
            if (oldShadowUser != null && oldShadowUser != shadowUser) {
                throw new InvalidException("Duplicate shadow username {}", username);
            }

            String socksUserName = shadowUser.getSocksUser();
            SocksUser socksUser = socksStore.get(socksUserName);
            if (socksUser == null) {
                socksUser = new SocksUser(socksUserName);
                socksUser.setPassword(socksPassword);
                SocksUser oldUser = ((ConcurrentHashMap<String, SocksUser>) socksStore).putIfAbsent(socksUserName, socksUser);
                if (oldUser != null) {
                    socksUser = oldUser;
                }
            }
        }

        Tasks.scheduleDaily(() -> {
            resetIp();

            if (DateTime.now().getDay() == 1) {
                resetData();
            }
        }, "01:00:00");
    }

    private void validateShadowUser(ShadowUser shadowUser) {
        if (shadowUser.getSsPort() <= 0) {
            throw new InvalidException("ShadowUser {} invalid ssPort={}", shadowUser, shadowUser.getSsPort());
        }
        if (Strings.isEmpty(shadowUser.getSsPwd())) {
            throw new InvalidException("ShadowUser {} ssPwd is empty", shadowUser);
        }
        if (Strings.isEmpty(shadowUser.getSocksUser())) {
            throw new InvalidException("ShadowUser {} socksUser is empty", shadowUser);
        }
        if (Strings.isEmpty(shadowUser.getUsername())) {
            throw new InvalidException("ShadowUser {} username is empty", shadowUser);
        }
    }

    @Override
    public SocksUser login(String username, String password) {
        AuthResult result = loginResult(username, password);
        return result == null ? null : result.getUser();
    }

    @Override
    public AuthResult loginResult(String username, String password) {
        ShadowUser shadowUser = shadowStore.get(username);
        if (shadowUser == null || !eq(socksPassword, password)) {
            return null;
        }
        return resolve(shadowUser);
    }

    public AuthResult resolve(String username) {
        ShadowUser shadowUser = shadowStore.get(username);
        return shadowUser == null ? null : resolve(shadowUser);
    }

    private AuthResult resolve(ShadowUser shadowUser) {
        // 认证返回内部 socks 用户，统计归属单独绑定到对应 SS 用户。
        SocksUser socksUser = socksStore.get(shadowUser.getSocksUser());
        if (socksUser == null) {
            return null;
        }
        return new AuthResult(socksUser, shadowUser);
    }

    public void resetIp() {
        for (ShadowUser user : shadowStore.values()) {
            Map<InetAddress, TrafficLoginInfo> loginIps = user.getLoginIps();
            for (Map.Entry<InetAddress, TrafficLoginInfo> lEntry : loginIps.entrySet()) {
                DateTime latestTime = lEntry.getValue().getLatestTime();
                if (latestTime != null && latestTime.before(DateTime.now().addDays(-2))) {
                    loginIps.remove(lEntry.getKey());
                }
            }
        }
    }

    public void resetData() {
        DateTime now = DateTime.now();
        for (ShadowUser user : shadowStore.values()) {
            for (TrafficLoginInfo l : user.getLoginIps().values()) {
                l.getTotalReadBytes().set(0);
                l.getTotalWriteBytes().set(0);
                l.getTotalReadPackets().set(0);
                l.getTotalWritePackets().set(0);
                l.getTotalActiveSeconds().set(0);
            }
            user.setLastResetTime(now);
        }
    }
}
