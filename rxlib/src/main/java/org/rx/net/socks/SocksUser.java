package org.rx.net.socks;

import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.annotation.JSONType;
import com.alibaba.fastjson2.reader.ObjectReader;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.rx.bean.DateTime;
import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.io.Bytes;
import org.rx.net.support.IPAddress;
import org.rx.net.support.IPSearcher;
import org.rx.util.BeanMapper;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@JSONType(deserializer = SocksUser.JsonReader.class)
@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class SocksUser implements Serializable {
    public static class JsonReader implements ObjectReader<SocksUser> {
        @Override
        public SocksUser readObject(JSONReader jsonReader, Type fieldType, Object fieldName, long features) {
            if (jsonReader.nextIfNull()) {
                return null;
            }
            Map<String, Object> map = jsonReader.readObject();
            SocksUser user = new SocksUser((String) map.get("username"));
            BeanMapper.DEFAULT.map(map, user);
            return user;
        }
    }

    @Getter
    @Setter
    @ToString
    public static class LoginInfo implements Serializable {
        private static final long serialVersionUID = 1264936011170722186L;
        final InetAddress ip;
        IPAddress ipInfo;
        DateTime latestTime;
        int refCnt;
        final AtomicLong totalActiveSeconds = new AtomicLong();
        AtomicLong totalReadBytes = new AtomicLong();
        final AtomicLong totalWriteBytes = new AtomicLong();

        public LoginInfo(InetAddress ip) {
            this.ip = ip;
            ipInfo = IPSearcher.DEFAULT.resolve(ip.getHostAddress());
        }
    }

    private static final long serialVersionUID = 7845976131633777320L;
    public static final SocksUser ANONYMOUS = new SocksUser("anonymous");

    final String username;
    final Map<InetAddress, LoginInfo> loginIps = new ConcurrentHashMap<>(8);
    String password;
    int maxIpCount;
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
}
