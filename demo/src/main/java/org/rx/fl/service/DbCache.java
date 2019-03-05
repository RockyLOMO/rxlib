package org.rx.fl.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.$;
import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.fl.repository.CacheItemMapper;
import org.rx.fl.repository.model.CacheItem;
import org.rx.fl.repository.model.CacheItemExample;
import org.rx.fl.util.HttpCaller;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.rx.beans.$.$;
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

    public String getShortUrl(String longUrl) {
        require(longUrl);

        $<String> responseText$ = $();
        try {
            return getOrStore(longUrl, k -> {
                String apiUrl = String.format("http://dwz.wailian.work/api.php?from=w&url=%s=&site=sina", App.convertToBase64String(longUrl.getBytes()));
                HttpCaller caller = new HttpCaller();
                Map<String, String> map = new HashMap<>();
                map.put("Cookie", "PHPSESSID=63btgsg62gursl7vtu17o96kj6; __51cke__=; td_cookie=3631118679; Hm_lvt_fd97a926d52ef868e2d6a33de0a25470=1550555992,1551777578; Hm_lpvt_fd97a926d52ef868e2d6a33de0a25470=1551777578; __tins__19242943=%7B%22sid%22%3A%201551777577820%2C%20%22vd%22%3A%201%2C%20%22expires%22%3A%201551779377820%7D; __51laig__=3");
                caller.setHeaders(map);
                responseText$.$ = caller.get(apiUrl);
                return JSON.parseObject(responseText$.$).getJSONObject("data").getString("short_url");
            }, 60 * 24 * 365);
        } catch (Exception e) {
            log.warn("getShortUrl -> {} {}", responseText$.$, e);
            return longUrl;
        }
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
