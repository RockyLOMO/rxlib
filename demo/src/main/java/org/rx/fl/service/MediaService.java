package org.rx.fl.service;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.rx.App;
import org.rx.fl.model.GoodsInfo;
import org.rx.fl.model.MediaType;
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
    private static final ConcurrentHashMap<MediaType, ConcurrentLinkedQueue<Media>> holder = new ConcurrentHashMap<>();

    private static ConcurrentLinkedQueue<Media> getQueue(MediaType type) {
        ConcurrentLinkedQueue<Media> queue = holder.get(type);
        if (queue == null) {
            synchronized (holder) {
                if ((queue = holder.get(type)) == null) {
                    holder.put(type, queue = new ConcurrentLinkedQueue<>());
                }
            }
        }
        return queue;
    }

    private static Media create(MediaType type) {
        ConcurrentLinkedQueue<Media> queue = getQueue(type);
        Media media = queue.poll();
        if (media == null) {
            switch (type) {
                case Jd:
                    media = new JdMedia();
                    break;
                default:
                    media = new TbMedia();
                    break;
            }
        }
        return media;
    }

    private static void release(Media media) {
        getQueue(media.getType()).add(media);
    }

    public static void init(byte size) {
        for (MediaType type : MediaType.values()) {
            size -= getQueue(type).size();
            for (int i = 0; i < size; i++) {
                create(type);
            }
        }
    }

    public static void invoke(MediaType type, Consumer<Media> consumer) {
        require(type, consumer);

        Media media = create(type);
        try {
            consumer.accept(media);
        } finally {
            release(media);
        }
    }

    public List<String> findAdv(String[] sourceArray) {
        List<String> list = new ArrayList<>();
        for (String source : sourceArray) {
            Media media = create(MediaType.Taobao);

            String url = media.findLink(source);

            GoodsInfo goods = media.findGoods(url);

            media.login();
            String code = media.findAdv(goods);

            Function<String, Double> convert = p -> {
                if (Strings.isNullOrEmpty(p)) {
                    return 0d;
                }
                return App.changeType(p.replace("￥", ""), double.class);
            };
            Double payAmount = convert.apply(goods.getPrice())
                    - convert.apply(goods.getBackMoney())
                    - convert.apply(goods.getCouponAmount());
            String content = String.format("约反%s 优惠券%s 付费价￥%.2f；复制框内整段文字，打开「手淘」即可「领取优惠券」并购买%s",
                    goods.getBackMoney(), goods.getCouponAmount(), payAmount, code);

            list.add(content);
        }
        return list;
    }
}
