package org.rx.net.socks;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.rx.bean.DateTime;
import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.io.Bytes;

import java.io.Serializable;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class SocksUser implements Serializable {
    @Getter
    @Setter
    @ToString
    public static class LoginInfo implements Serializable {
        private static final long serialVersionUID = 1264936011170722186L;
        DateTime latestTime;
        int refCnt;
        final AtomicLong totalActiveSeconds = new AtomicLong();
        final AtomicLong totalReadBytes = new AtomicLong();
        final AtomicLong totalWriteBytes = new AtomicLong();
    }

    private static final long serialVersionUID = 7845976131633777320L;
    public static final SocksUser ANONYMOUS = new SocksUser();

    final String username;
    final Map<InetAddress, LoginInfo> loginIps = new ConcurrentHashMap<>(4);
    String password;
    /**
     * 默认0 = 不启用账号
     */
    int ipLimit;
    DateTime lastResetTime;

    public boolean isAnonymous() {
        return Strings.hashEquals(ANONYMOUS.getUsername(), username);
    }

    public long getTotalReadBytes() {
        return (long) Linq.from(loginIps.values()).sum(p -> p.totalReadBytes.get());
    }

    public long getTotalWriteBytes() {
        return (long) Linq.from(loginIps.values()).sum(p -> p.totalWriteBytes.get());
    }

    public String getHumanTotalReadBytes() {
        return Bytes.readableByteSize(getTotalReadBytes());
    }

    public String getHumanTotalWriteBytes() {
        return Bytes.readableByteSize(getTotalWriteBytes());
    }

    public SocksUser() {
        username = "anonymous";
    }
}
