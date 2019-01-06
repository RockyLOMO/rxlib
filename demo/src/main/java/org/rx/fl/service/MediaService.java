package org.rx.fl.service;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.App;
import org.rx.common.ManualResetEvent;
import org.rx.fl.model.AdvNotFoundReason;
import org.rx.fl.model.GoodsInfo;
import org.rx.fl.model.MediaType;
import org.rx.fl.service.media.JdMedia;
import org.rx.fl.service.media.Media;
import org.rx.fl.service.media.TbMedia;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static org.rx.Contract.require;

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
        Integer size = (Integer) App.readSetting("app.media.coreSize");
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

    private void invoke(MediaType type, Consumer<Media> consumer) {
        require(type, consumer);

        Media media = create(type, true);
        try {
            consumer.accept(media);
        } finally {
            release(media);
        }
    }

    public List<String> findAdv(String[] sourceArray) {
        List<String> list = new ArrayList<>();
        for (String source : sourceArray) {
            invoke(MediaType.Taobao, media -> {
                String url = media.findLink(source);
                if (Strings.isNullOrEmpty(url)) {
                    list.add(AdvNotFoundReason.NoLink.name());
                    return;
                }
                GoodsInfo goods = media.findGoods(url);
                if (goods == null || Strings.isNullOrEmpty(goods.getSellerName())) {
                    list.add(AdvNotFoundReason.NoGoods.name());
                    return;
                }
                media.login();
                String code = media.findAdv(goods);
                if (Strings.isNullOrEmpty(code)) {
                    list.add(AdvNotFoundReason.NoAdv.name());
                    return;
                }

                String content;
                Function<String, Double> convert = p -> {
                    if (Strings.isNullOrEmpty(p)) {
                        return 0d;
                    }
                    return App.changeType(p.replace("￥", "")
                            .replace("¥", ""), double.class);
                };
                Double payAmount = convert.apply(goods.getPrice())
                        - convert.apply(goods.getRebateAmount())
                        - convert.apply(goods.getCouponAmount());
                content = String.format("约反      %s\n优惠券  ￥%s\n付费价  ￥%.2f\n复制框内整段文字，打开「手淘」即可「领取优惠券」并购买%s",
                        goods.getRebateAmount(), goods.getCouponAmount(), payAmount, code);
//                try {
//                    content = String.format("http://taoyouhui.ml/tb.html#/%s/%s",
//                            code.replace("￥", ""),
//                            URLEncoder.encode(goods.getImageUrl(), "utf-8"));
//                } catch (Exception e) {
//                    throw new InvalidOperationException(e);
//                }

                list.add(content);
            });
        }
        return list;
    }
}
