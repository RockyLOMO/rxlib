package org.rx.fl.service;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.fl.repository.CacheItemMapper;
import org.rx.fl.repository.model.CacheItem;
import org.rx.fl.repository.model.CacheItemExample;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.function.Function;

import static org.rx.common.Contract.require;
import static org.rx.util.AsyncTask.TaskFactory;

@Component
@Slf4j
public class DbCache {
    @Resource
    private CacheItemMapper cacheItemMapper;

    public DbCache() {
        TaskFactory.schedule(() -> {
            try {
                CacheItemExample q = new CacheItemExample();
                q.createCriteria().andExpireTimeLessThan(DateTime.now());
                cacheItemMapper.deleteByExample(q);
            } catch (Exception e) {
                log.error("DbCache", e);
            }
        }, 64 * 1000);
    }

    public <T> T getOrStore(String key, Function<String, T> supplier, int liveMinutes) {
        T val = get(key);
        if (val == null) {
            val = add(key, supplier.apply(key), liveMinutes);
        }
        return val;
    }

    public <T> T get(String key) {
        require(key);
        String cacheKey = App.cacheKey(key);

        CacheItem cacheItem = cacheItemMapper.selectByPrimaryKey(cacheKey);
        if (cacheItem == null || cacheItem.getExpireTime().before(DateTime.now())) {
            return null;
        }
        JSONObject json = JSONObject.parseObject(cacheItem.getValue());
        Class type = App.loadClass(json.getString("type"), false);
        return (T) json.getObject("value", type);
    }

    public <T> T add(String key, T value, int liveMinutes) {
        require(key, value);
        String cacheKey = App.cacheKey(key);

        CacheItem cacheItem = cacheItemMapper.selectByPrimaryKey(cacheKey);
        boolean isInsert = false;
        if (cacheItem == null) {
            isInsert = true;
            cacheItem = new CacheItem();
            cacheItem.setKey(cacheKey);
            cacheItem.setCreateTime(DateTime.now());
        }
        JSONObject json = new JSONObject();
        json.put("type", value.getClass().getName());
        json.put("value", value);
        cacheItem.setValue(json.toJSONString());

        DateTime expireTime = DateTime.now().addMinutes(liveMinutes);
        DateTime maxTime = DateTime.valueOf(String.format("%s 23:56:00", DateTime.now().toDateString()), DateTime.Formats.first());
        if (expireTime.after(maxTime)) {
            expireTime = maxTime;
        }
        cacheItem.setExpireTime(expireTime);
        if (isInsert) {
            cacheItemMapper.insert(cacheItem);
        } else {
            cacheItemMapper.updateByPrimaryKey(cacheItem);
        }
        return value;
    }

    public void remove(String key) {
        require(key);
        String cacheKey = App.cacheKey(key);

        cacheItemMapper.deleteByPrimaryKey(cacheKey);
    }
}
