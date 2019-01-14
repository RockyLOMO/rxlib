package org.rx.fl.service.media;

import org.rx.beans.DateTime;
import org.rx.cache.LRUCache;
import org.rx.common.App;
import org.rx.fl.dto.media.GoodsInfo;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;

import java.util.List;
import java.util.function.Function;

public interface Media {
    int cacheSeconds = App.readSetting("app.media.cacheSeconds");

    default <T> T getOrStore(String key, Function<String, T> supplier) {
        LRUCache<String, Object> cache = LRUCache.getInstance();
        Object val = cache.get(key);
        if (val == null) {
            cache.add(key, val = supplier.apply(key), cacheSeconds, null);
        }
        return (T) val;
    }

    MediaType getType();

    boolean isLogin();

    void login();

    String findLink(String content);

    GoodsInfo findGoods(String url);

    String findCouponAmount(String url);

    String findAdv(GoodsInfo goodsInfo);

    List<OrderInfo> findOrders(DateTime start, DateTime end);
}
