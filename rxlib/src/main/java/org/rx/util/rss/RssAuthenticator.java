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
import java.util.concurrent.TimeUnit;

import static org.rx.core.Extends.eq;

public class RssAuthenticator implements Authenticator {
    @Getter
    private final Map<String, ShadowUser> shadowStore = new ConcurrentHashMap<>();
    @Getter
    private final Map<String, SocksUser> socksStore = new ConcurrentHashMap<>();
    @Getter
    private final String socksPassword;
    @Getter
    private final int memoryRetentionHours;

    public RssAuthenticator(List<ShadowUser> shadowUsers, String socksPassword) {
        this(shadowUsers, socksPassword, 24);
    }

    public RssAuthenticator(List<ShadowUser> shadowUsers, String socksPassword, int memoryRetentionHours) {
        this.socksPassword = socksPassword;
        this.memoryRetentionHours = Math.max(1, memoryRetentionHours);
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

        resetIp();
        Tasks.schedulePeriod(this::resetIp, TimeUnit.HOURS.toMillis(1));
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
        DateTime now = DateTime.now();
        DateTime expireBefore = now.addHours(-currentMemoryRetentionHours());
        for (ShadowUser user : shadowStore.values()) {
            Map<InetAddress, TrafficLoginInfo> loginIps = user.getLoginIps();
            for (Map.Entry<InetAddress, TrafficLoginInfo> lEntry : loginIps.entrySet()) {
                TrafficLoginInfo info = lEntry.getValue();
                DateTime latestTime = info.getLatestTime();
                if (info.getRefCnt().get() <= 0 && (latestTime == null || latestTime.before(expireBefore))) {
                    loginIps.remove(lEntry.getKey());
                }
            }
            user.setLastResetTime(expireBefore);
        }
    }

    private int currentMemoryRetentionHours() {
        RSSConf conf = RssClient.rssConf;
        return conf != null ? Math.max(1, conf.memoryRetentionHours) : memoryRetentionHours;
    }

}
