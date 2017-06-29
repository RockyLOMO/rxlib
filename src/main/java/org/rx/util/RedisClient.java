package org.rx.util;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisShardInfo;

import java.nio.charset.Charset;
import java.util.Map;
import java.util.Set;

/**
 * Created by wangxiaoming on 2016/3/29.
 *
 * @author http://blog.csdn.net/java2000_wl
 */
@Component
public class RedisClient {
    private static RedissonClient redisson;

    //https://github.com/mrniko/redisson/wiki/8.-Distributed-locks-and-synchronizers
    public synchronized static RedissonClient getRedisson() {
        if (redisson == null) {
            Map<String, String> map = App.readSettings("app");
            Config config = new Config();
            config.useSingleServer()
                    .setAddress(String.format("%s:%s", map.get("redis.host"), map.get("redis.port")))
                    .setTimeout(App.convert(map.get("redis.timeout"), Integer.class));
            redisson = Redisson.create(config);
        }
        return redisson;
    }

    private static RedisTemplate<String, Object> Template;
    @Autowired
    private RedisTemplate<String, Object> template;
    private String keyPrefix;

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        if (template != null) {
            throw new IllegalArgumentException("Autowired Instance");
        }
        this.keyPrefix = keyPrefix;
    }

    private RedisTemplate<String, Object> getTemplate() {
        if (template == null && Template == null) {
            Map<String, String> map = App.readSettings("app");
            JedisShardInfo config = new JedisShardInfo(map.get("redis.host"), Integer.parseInt(map.get("redis.port")));
            JedisConnectionFactory fac = new JedisConnectionFactory(config);
            fac.setTimeout(App.convert(map.get("redis.timeout"), Integer.class));
            fac.setUsePool(true);
            Template = new RedisTemplate<>();
            Template.setConnectionFactory(fac);
            Template.setKeySerializer(new org.springframework.data.redis.serializer.StringRedisSerializer(Charset.forName("UTF8")));
            Template.setValueSerializer(new org.springframework.data.redis.serializer.JdkSerializationRedisSerializer());
            Template.afterPropertiesSet();
        }
        return App.isNull(template, Template);
    }

    private byte[] getKeyBytes(String key) {
        try {
            key = App.isNull(keyPrefix, "") + key;
            return key.getBytes(App.utf8);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public void set(String key, Object value) {
        this.set(key, value, 0L);
    }

    public void set(final String key, final Object value, final long liveTime) {
        getTemplate().execute(new RedisCallback() {
            public Long doInRedis(RedisConnection client) throws DataAccessException {
                byte[] theKey = getKeyBytes(key);
                client.set(theKey, App.serialize(value));
                if (liveTime > 0) {
                    client.expire(theKey, liveTime);
                }
                return 1L;
            }
        });
    }

    public Object get(final String key) {
        return getTemplate().execute(new RedisCallback() {
            public Object doInRedis(RedisConnection client) throws DataAccessException {
                byte[] theKey = getKeyBytes(key);
                byte[] theVal = client.get(theKey);
                if (theVal == null || theVal.length == 0) {
                    return null;
                }
                return App.deserialize(theVal);
            }
        });
    }

    public long del(final String... keys) {
        return (long) getTemplate().execute(new RedisCallback() {
            public Long doInRedis(RedisConnection client) throws DataAccessException {
                long result = 0;
                for (String key : keys) {
                    result += client.del(getKeyBytes(key));
                }
                return result;
            }
        });
    }

    public Set<String> keys(String pattern) {
        return getTemplate().keys(pattern);
    }

    public long dbSize() {
        return (long) getTemplate().execute(new RedisCallback<Object>() {
            public Long doInRedis(RedisConnection client) throws DataAccessException {
                return client.dbSize();
            }
        });
    }

    public boolean exists(final String key) {
        return (boolean) getTemplate().execute(new RedisCallback() {
            public Boolean doInRedis(RedisConnection client) throws DataAccessException {
                return client.exists(getKeyBytes(key));
            }
        });
    }

    public void flushDb() {
        getTemplate().execute(new RedisCallback() {
            public Object doInRedis(RedisConnection client) throws DataAccessException {
                client.flushDb();
                return null;
            }
        });
    }
}
