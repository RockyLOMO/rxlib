package org.rx.fl.service;

import com.google.common.base.Strings;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.DateTime;
import org.rx.common.*;
import org.rx.fl.dto.media.*;
import org.rx.fl.dto.repo.UserInfo;
import org.rx.fl.repository.model.Order;
import org.rx.fl.service.media.JdMedia;
import org.rx.fl.service.media.Media;
import org.rx.fl.service.media.TbMedia;
import org.rx.util.ManualResetEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.rx.common.Contract.require;
import static org.rx.common.Contract.toJsonString;
import static org.rx.fl.util.DbUtil.toMoney;
import static org.rx.util.AsyncTask.TaskFactory;

@Service
@Slf4j
public class MediaService {
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

    @Resource
    private OrderService orderService;
    @Resource
    private UserService userService;
    @Resource
    private NotifyService notifyService;
    @Resource
    private MediaCache cache;
    private MediaConfig config;

    public List<MediaType> getMedias() {
        return NQuery.of(holder.keySet()).toList();
    }

    @Autowired
    public MediaService(MediaConfig config) {
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
        TaskFactory.schedule(() -> syncOrder(-31), config.getSyncMonthlyOrderSeconds() * 1000);
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
                List<Order> paidOrders = new ArrayList<>(), settleOrders = new ArrayList<>();
                orderService.saveOrders(orders, paidOrders, settleOrders);
                for (Order paidOrder : paidOrders) {
                    UserInfo user = userService.queryUser(paidOrder.getUserId());
                    String content = String.format("一一一一支 付 成 功一一一一\n" +
                                    "%s\n" +
                                    "订单编号:\n" +
                                    "%s\n" +
                                    "付费金额: %.2f元\n" +
                                    "红包补贴: %.2f元\n" +
                                    "\n" +
                                    "可提现金额: %.2f元\n" +
                                    "未收货金额: %.2f元\n" +
                                    "-------------------------------\n" +
                                    "亲 收货成功后，回复 提现 两个字，给你补贴红包\n" +
                                    "\n" +
                                    "将机器人名片推荐给好友，永久享受返利提成", paidOrder.getGoodsName(), paidOrder.getOrderNo(),
                            toMoney(paidOrder.getPayAmount()), toMoney(paidOrder.getRebateAmount()),
                            toMoney(user.getBalance()), toMoney(user.getUnconfirmedOrderAmount()));
                    notifyService.add(user.getUserId(), Collections.singletonList(content));
                }
                for (Order settleOrder : settleOrders) {
                    UserInfo user = userService.queryUser(settleOrder.getUserId());
                    String content = String.format("一一一一收 货 成 功一一一一\n" +
                                    "%s\n" +
                                    "订单编号:\n" +
                                    "%s\n" +
                                    "付费金额: %.2f元\n" +
                                    "红包补贴: %.2f元\n" +
                                    "\n" +
                                    "可提现金额: %.2f元\n" +
                                    "未收货金额: %.2f元\n" +
                                    "总成功订单: %s单\n" +
                                    "-------------------------------\n" +
                                    "回复 提现 两个字，给你补贴红包\n" +
                                    "补贴红包已转入可提现金额", settleOrder.getGoodsName(), settleOrder.getOrderNo(),
                            toMoney(settleOrder.getPayAmount()), toMoney(settleOrder.getSettleAmount()),
                            toMoney(user.getBalance()), toMoney(user.getUnconfirmedOrderAmount()), user.getConfirmedOrderCount());
                    notifyService.add(user.getUserId(), Collections.singletonList(content));
                }
            } catch (Exception e) {
                log.error("syncOrder", e);
            }
        }
    }

    private <T> T invoke(MediaType type, Function<Media, T> function) {
        require(type, function);

        Media media = create(type, true);
        try {
            return function.apply(media);
        } finally {
            release(media);
        }
    }

    public List<OrderInfo> findOrders(MediaType type, DateTime start, DateTime end) {
        require(type, start, end);

        return invoke(type, p -> p.findOrders(start, end));
    }

    public String findLink(String content) {
        require(content);

        for (MediaType type : getMedias()) {
            String link = invoke(type, p -> p.findLink(content));
            if (!Strings.isNullOrEmpty(link)) {
                return link;
            }
        }
        return "";
    }

    public FindAdvResult findAdv(String content) {
        require(content);

        FindAdvResult result = null;
        for (MediaType type : getMedias()) {
            result = invoke(type, media -> {
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
            });
            if (result.getFoundStatus() == AdvFoundStatus.Ok) {
                break;
            }
        }
        return result;
    }
}
