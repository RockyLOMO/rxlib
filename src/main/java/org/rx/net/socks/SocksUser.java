package org.rx.net.socks;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.annotation.JSONType;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.deserializer.ObjectDeserializer;
import lombok.Data;
import org.rx.bean.DateTime;
import org.rx.io.Bytes;
import org.rx.util.BeanMapper;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@JSONType(deserializer = SocksUser.Serializer.class)
@Data
public class SocksUser implements Serializable {
    public static class Serializer implements ObjectDeserializer {
        @Override
        public <T> T deserialze(DefaultJSONParser parser, Type fieldType, Object fieldName) {
            JSONObject map = parser.parseObject();
            SocksUser user = new SocksUser(map.getString("username"));
            BeanMapper.INSTANCE.map(map, user);
            return (T) user;
        }

        @Override
        public int getFastMatchToken() {
            return 0;
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
        return ANONYMOUS.getUsername().equals(username);
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
