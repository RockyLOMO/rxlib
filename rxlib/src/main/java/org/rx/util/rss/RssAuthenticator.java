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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.rx.core.Extends.eq;

public class RssAuthenticator implements Authenticator {
    static final int DEFAULT_MEMORY_RETENTION_HOURS = 5;

    @Getter
    private final Map<String, ShadowUser> shadowStore = new ConcurrentHashMap<>();
    @Getter
    private final Map<String, SocksUser> socksStore = new ConcurrentHashMap<>();
    private final AtomicReference<AuthSettings> settings = new AtomicReference<>(new AuthSettings(null, DEFAULT_MEMORY_RETENTION_HOURS));

    public RssAuthenticator(List<ShadowUser> shadowUsers, String socksPassword) {
        this(shadowUsers, socksPassword, DEFAULT_MEMORY_RETENTION_HOURS);
    }

    public RssAuthenticator(List<ShadowUser> shadowUsers, String socksPassword, int memoryRetentionHours) {
        reload(shadowUsers, socksPassword, memoryRetentionHours);
        resetIp();
        Tasks.schedulePeriod(this::resetIp, TimeUnit.HOURS.toMillis(DEFAULT_MEMORY_RETENTION_HOURS));
    }

    public synchronized void reload(List<ShadowUser> shadowUsers, String socksPassword, int memoryRetentionHours) {
        if (shadowUsers == null) {
            throw new InvalidException("shadowUsers is empty");
        }
        if (Strings.isEmpty(socksPassword)) {
            throw new InvalidException("socksPassword is empty");
        }
        Map<String, ShadowUser> nextShadowStore = new ConcurrentHashMap<>();
        Map<String, SocksUser> nextSocksStore = new ConcurrentHashMap<>();
        Set<String> usernames = new HashSet<>();
        for (ShadowUser shadowUser : shadowUsers) {
            if (shadowUser == null) {
                continue;
            }
            validateShadowUser(shadowUser);
            String username = shadowUser.getUsername();
            if (!usernames.add(username)) {
                throw new InvalidException("Duplicate shadow username {}", username);
            }
            shadowUser.setUsername(username);
            ShadowUser oldShadowUser = shadowStore.get(username);
            if (oldShadowUser != null && oldShadowUser != shadowUser) {
                shadowUser.getLoginIps().putAll(oldShadowUser.getLoginIps());
                if (shadowUser.getLastResetTime() == null) {
                    shadowUser.setLastResetTime(oldShadowUser.getLastResetTime());
                }
            }
            nextShadowStore.put(username, shadowUser);

            String socksUserName = shadowUser.getSocksUser();
            SocksUser socksUser = nextSocksStore.get(socksUserName);
            if (socksUser == null) {
                SocksUser oldSocksUser = socksStore.get(socksUserName);
                socksUser = oldSocksUser != null ? oldSocksUser : new SocksUser(socksUserName);
                socksUser.setPassword(socksPassword);
                nextSocksStore.put(socksUserName, socksUser);
            }
        }

        shadowStore.clear();
        shadowStore.putAll(nextShadowStore);
        socksStore.clear();
        socksStore.putAll(nextSocksStore);
        settings.set(new AuthSettings(socksPassword, Math.max(1, memoryRetentionHours)));
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
        if (shadowUser == null || !eq(settings.get().socksPassword, password)) {
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
            boolean hasConnectionData = false;
            for (Map.Entry<InetAddress, TrafficLoginInfo> lEntry : loginIps.entrySet()) {
                TrafficLoginInfo info = lEntry.getValue();
                if (hasConnectionData(info)) {
                    hasConnectionData = true;
                }
                if (info == null) {
                    loginIps.remove(lEntry.getKey());
                    continue;
                }
                DateTime latestTime = info.getLatestTime();
                if (info.getRefCnt().get() <= 0 && (latestTime == null || latestTime.before(expireBefore))) {
                    loginIps.remove(lEntry.getKey());
                }
            }
            if (hasConnectionData || !loginIps.isEmpty()) {
                user.setLastResetTime(expireBefore);
            }
        }
    }

    private boolean hasConnectionData(TrafficLoginInfo info) {
        return info != null && (info.getRefCnt().get() > 0
                || info.getLatestTime() != null
                || info.getTotalActiveSeconds().get() > 0L
                || info.getTotalReadBytes().get() > 0L
                || info.getTotalWriteBytes().get() > 0L
                || info.getTotalReadPackets().get() > 0L
                || info.getTotalWritePackets().get() > 0L);
    }

    private int currentMemoryRetentionHours() {
        RssClientConf conf = RssClient.rssConf;
        return conf != null ? Math.max(1, conf.memoryRetentionHours) : getMemoryRetentionHours();
    }

    public String getSocksPassword() {
        return settings.get().socksPassword;
    }

    public int getMemoryRetentionHours() {
        return settings.get().memoryRetentionHours;
    }

    static final class AuthSettings {
        final String socksPassword;
        final int memoryRetentionHours;

        AuthSettings(String socksPassword, int memoryRetentionHours) {
            this.socksPassword = socksPassword;
            this.memoryRetentionHours = memoryRetentionHours;
        }
    }

}
