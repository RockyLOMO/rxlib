package org.rx.fl.service;

import com.google.common.base.Strings;
import io.netty.util.internal.ThreadLocalRandom;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.rx.beans.DateTime;
import org.rx.common.*;
import org.rx.fl.dto.media.*;
import org.rx.fl.service.media.JdMedia;
import org.rx.fl.service.media.Media;
import org.rx.fl.service.media.TbMedia;
import org.rx.fl.service.order.NotifyOrdersInfo;
import org.rx.fl.service.order.OrderService;
import org.rx.fl.service.user.UserService;
import org.rx.util.ManualResetEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.rx.common.Contract.*;
import static org.rx.util.AsyncTask.TaskFactory;

@Service
@Slf4j
public class MediaService {
    //region queue
    @Data
    private static class HoldItem {
        private final ConcurrentLinkedQueue<Media> queue = new ConcurrentLinkedQueue<>();
        private final ManualResetEvent waiter = new ManualResetEvent();
    }

    private static final ConcurrentHashMap<MediaType, HoldItem> holder = new ConcurrentHashMap<>();

    private static HoldItem getHoldItem(MediaType type) {
        HoldItem holdItem = holder.get(type);
        if (holdItem == null) {
            synchronized (holder) {
                if ((holdItem = holder.get(type)) == null) {
                    holder.put(type, holdItem = new HoldItem());
                }
            }
        }
        return holdItem;
    }

    private Media create(MediaType type, boolean fromPool) {
        Media media;
        if (fromPool) {
            HoldItem holdItem = getHoldItem(type);
            while ((media = holdItem.queue.poll()) == null) {
                log.info("wait media from pool");
                holdItem.waiter.waitOne();
            }
            holdItem.waiter.reset();
            log.info("wait ok and get media");
            return media;
        }

        switch (type) {
            case Jd:
                media = new JdMedia(config) {
                    @Override
                    public String findCouponAmount(String url) {
                        return cache.getOrStore(url, k -> super.findCouponAmount(url), config.getGoodsCacheMinutes());
                    }
                };
                break;
            case Taobao:
                media = new TbMedia(config) {
                    @Override
                    public String findCouponAmount(String url) {
                        return cache.getOrStore(url, k -> super.findCouponAmount(url), config.getGoodsCacheMinutes());
                    }
                };
                break;
            default:
                throw new InvalidOperationException("Not supported");
        }
        return media;
    }

    private void release(Media media) {
        HoldItem holdItem = getHoldItem(media.getType());
        holdItem.queue.add(media);
        holdItem.waiter.set();
        log.info("release media and waitHandle");
    }
    //endregion

    @Resource
    private OrderService orderService;
    @Resource
    private UserService userService;
    @Resource
    private MediaCache cache;
    private MediaConfig config;

    public List<MediaType> getMedias() {
        return NQuery.of(holder.keySet()).toList();
    }

    @Autowired
    public MediaService(MediaConfig config, UserConfig userConfig) {
        this.config = config;
        BiConsumer<MediaType, Integer> consumer = (p1, p2) -> {
            for (int i = 0; i < p2; i++) {
                release(create(p1, false));
            }
            log.info("Create {} media {} size", p1, p2);
        };
        for (MediaType type : NQuery.of(config.getEnableMedias().split(",")).select(p -> MediaType.valueOf(p))) {
            int coreSize = 1;
            switch (type) {
                case Taobao:
                    coreSize = config.getTaobao().getCoreSize();
                    break;
                case Jd:
                    coreSize = config.getJd().getCoreSize();
                    break;
            }
            consumer.accept(type, coreSize);
        }

        TaskFactory.schedule(() -> syncOrder(8), config.getSyncWeeklyOrderSeconds() * 1000);
        TaskFactory.scheduleDaily(() -> syncOrder(-31), config.getSyncMonthlyOrderTime());
        TaskFactory.scheduleDaily(() -> recommendGoods(userConfig), userConfig.getGroupGoodsTime());
    }

    private void syncOrder(int daysAgo) {
        for (MediaType media : getMedias()) {
            try {
                DateTime now = DateTime.now();
                DateTime start = now.addDays(-daysAgo);
                List<OrderInfo> orders = findOrders(media, start, now);
                log.info("syncOrder {}", toJsonString(orders));
                if (CollectionUtils.isEmpty(orders)) {
                    continue;
                }
                NotifyOrdersInfo notify = new NotifyOrdersInfo();
                orderService.saveOrders(orders, notify);
                userService.pushMessage(notify);
            } catch (Exception e) {
                log.error("syncOrder", e);
            }
        }
    }

    private void recommendGoods(UserConfig userConfig) {
        String[] goodsNames = userConfig.getGroupGoodsName();
        if (ArrayUtils.isEmpty(goodsNames)) {
            return;
        }

        int offset = ThreadLocalRandom.current().nextInt(0, goodsNames.length - 1);
        String goods = goodsNames[offset];
        for (MediaType media : getMedias()) {
            try {
                FindAdvResult commissionAdv = getHighCommissionAdv(media, goods);
                if (commissionAdv == null || commissionAdv.getFoundStatus() != AdvFoundStatus.Ok) {
                    log.warn("recommendGoods {} empty result", goods);
                    continue;
                }
                userService.pushMessage(commissionAdv);
                break;
            } catch (Exception e) {
                log.error("recommendGoods", e);
            }
        }
    }

    public FindAdvResult getHighCommissionAdv(MediaType type, String goodsName) {
        require(type, goodsName);

        return invoke(type, p -> p.getHighCommissionAdv(goodsName));
    }

    public List<OrderInfo> findOrders(MediaType type, DateTime start, DateTime end) {
        require(type, start, end);

        return invoke(type, p -> p.findOrders(start, end));
    }

    public String findLink(String content) {
        require(content);

        return isNull(invokeAll(p -> p.findLink(content), p -> !Strings.isNullOrEmpty(p)), "");
    }

    public FindAdvResult findAdv(String content, Function<FindAdvResult, String> getPromotionId) {
        require(content);

        FindAdvResult result = null;
        for (int i = 0; i < 2; i++) {
            result = invokeAll(media -> {
                FindAdvResult adv = new FindAdvResult();
                adv.setMediaType(media.getType());
                adv.setLink(media.findLink(content));

                if (Strings.isNullOrEmpty(adv.getLink())) {
                    adv.setFoundStatus(AdvFoundStatus.NoLink);
                    return adv;
                }

                //注意同一个商品分享来源连接会不一致
                GoodsInfo goodsInfo = cache.get(adv.getLink());
//                log.info("load goods from cache {}", JSON.toJSONString(goodsInfo));
                if (goodsInfo == null) {
                    cache.add(adv.getLink(), goodsInfo = media.findGoods(adv.getLink()), config.getGoodsCacheMinutes());
                }
                if (getPromotionId != null) {
                    goodsInfo.setPromotionId(getPromotionId.apply(adv));
                }
                adv.setGoods(goodsInfo);
                if (adv.getGoods() == null || Strings.isNullOrEmpty(adv.getGoods().getId())) {
                    adv.setFoundStatus(AdvFoundStatus.NoGoods);
                    return adv;
                }

                media.login();
                String key = adv.getMediaType().getValue() + "" + adv.getGoods().getId();
                FindAdvResult item = cache.get(key);
                if (item == null) {
                    item = new FindAdvResult();
                    item.setShareCode(App.retry(2, p -> media.findAdv(adv.getGoods()), null));
                    if (!Strings.isNullOrEmpty(item.getShareCode())) {
                        item.setGoods(adv.getGoods());
                        cache.add(key, item, config.getAdvCacheMinutes());
                    }
                }
                adv.setGoods(item.getGoods());
                adv.setShareCode(item.getShareCode());
                if (Strings.isNullOrEmpty(adv.getShareCode())) {
                    adv.setFoundStatus(AdvFoundStatus.NoAdv);
                    return adv;
                }

                adv.setFoundStatus(AdvFoundStatus.Ok);
                return adv;
            }, p -> p.getFoundStatus() == AdvFoundStatus.Ok);
            if (result != null && result.getFoundStatus() == AdvFoundStatus.Ok) {
                break;
            }
        }
        return result;
    }

    private <T> T invokeAll(Function<Media, T> eachFunc, Predicate<T> check) {
        require(eachFunc, check);

        for (MediaType type : getMedias()) {
            try {
                T t = invoke(type, eachFunc);
                if (check.test(t)) {
                    return t;
                }
            } catch (Exception e) {
                log.error("invokeAll", e);
            }
        }
        return null;
    }

    private <T> T invoke(MediaType type, Function<Media, T> func) {
        Media media = create(type, true);
        try {
            return func.apply(media);
        } finally {
            release(media);
        }
    }
}
