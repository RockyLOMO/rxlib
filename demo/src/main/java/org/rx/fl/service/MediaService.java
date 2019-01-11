package org.rx.fl.service;

import com.google.common.base.Strings;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.common.MediaConfig;
import org.rx.common.NQuery;
import org.rx.fl.dto.media.AdvFoundStatus;
import org.rx.fl.dto.media.FindAdvResult;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.service.media.JdMedia;
import org.rx.fl.service.media.Media;
import org.rx.fl.service.media.TbMedia;
import org.rx.util.ManualResetEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

import static org.rx.common.Contract.require;
import static org.rx.util.AsyncTask.TaskFactory;

@Service
@Slf4j
public class MediaService {
    @Data
    private static class HoldItem {
        private final ConcurrentLinkedQueue<Media> queue = new ConcurrentLinkedQueue<>();
        private final ManualResetEvent waiter = new ManualResetEvent(false);
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

    private static Media create(MediaType type, boolean fromPool) {
        Media media = null;
        HoldItem holdItem = null;
        if (fromPool) {
            holdItem = getHoldItem(type);
            media = holdItem.queue.poll();
            log.info("get media from pool {}", media == null ? "fail" : "ok");
        }
        if (media == null) {
            switch (type) {
                case Jd:
                    media = new JdMedia();
                    break;
                default:
                    if (holdItem == null) {
                        media = new TbMedia();
                    } else {
                        while ((media = holdItem.queue.poll()) == null) {
                            log.info("wait media from pool");
                            holdItem.waiter.waitOne();
                        }
                        log.info("wait ok and get media");
                    }
                    break;
            }
        }
        return media;
    }

    @SneakyThrows
    private static void release(Media media) {
        HoldItem holdItem = getHoldItem(media.getType());
        holdItem.queue.add(media);
        holdItem.waiter.set();
        log.info("release media and waitHandle");
        Thread.sleep(50);
        holdItem.waiter.reset();
        log.info("reset waitHandle");
    }

    static {
        Integer size = App.readSetting("app.media.coreSize");
        if (size == null) {
            size = 1;
        }
        log.info("init each media {} size", size);
        for (MediaType type : MediaType.values()) {
            for (int i = 0; i < size; i++) {
                release(create(type, false));
            }
        }
    }

    @Resource
    private OrderService orderService;
    @Resource
    private MediaConfig mediaConfig;

    public List<MediaType> getMedias() {
        return NQuery.of(holder.keySet()).toList();
    }

    public MediaService() {
        TaskFactory.schedule(() -> {
            for (MediaType media : getMedias()) {
                try {
                    DateTime now = DateTime.now();
                    DateTime start = now.addDays(-3);
                    List<OrderInfo> orders = findOrders(media, start, now);
                    if (CollectionUtils.isEmpty(orders)) {
                        continue;
                    }
                    orderService.saveOrders(orders);
                } catch (Exception e) {
                    log.error("saveOrders", e);
                }
            }
        }, 60 * 1000, mediaConfig.getSyncOrderPeriod() * 1000, "syncOrder");
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

                adv.setGoods(media.findGoods(adv.getLink()));
                if (adv.getGoods() == null || Strings.isNullOrEmpty(adv.getGoods().getSellerName())) {
                    adv.setFoundStatus(AdvFoundStatus.NoGoods);
                    return adv;
                }

                media.login();
                adv.setShareCode(media.findAdv(adv.getGoods()));
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
