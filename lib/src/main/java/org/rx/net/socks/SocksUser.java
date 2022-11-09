package org.rx.net.socks;

import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.annotation.JSONType;
import com.alibaba.fastjson2.reader.ObjectReader;
import lombok.Data;
import org.rx.bean.DateTime;
import org.rx.core.Strings;
import org.rx.io.Bytes;
import org.rx.util.BeanMapper;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@JSONType(deserializer = SocksUser.JsonReader.class)
@Data
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

    private static final long serialVersionUID = 7845976131633777320L;
    public static final SocksUser ANONYMOUS = new SocksUser("anonymous");

    private final String username;
    private String password;
    private int maxIpCount;
    private final Map<InetAddress, AtomicInteger> loginIps = new ConcurrentHashMap<>();
    private DateTime latestLoginTime;
    private final AtomicLong totalReadBytes = new AtomicLong();
    private final AtomicLong totalWriteBytes = new AtomicLong();

    public boolean isAnonymous() {
        return Strings.hashEquals(ANONYMOUS.getUsername(), username);
    }

    public String humanLatestLoginTime() {
        return latestLoginTime.toString();
    }

    public String humanTotalReadBytes() {
        return Bytes.readableByteSize(totalReadBytes.get());
    }

    public String humanTotalWriteBytes() {
        return Bytes.readableByteSize(totalWriteBytes.get());
    }
}
