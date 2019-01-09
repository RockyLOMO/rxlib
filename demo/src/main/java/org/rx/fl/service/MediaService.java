package org.rx.fl.service;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.common.App;
import org.rx.common.NQuery;
import org.rx.fl.dto.media.AdvFoundStatus;
import org.rx.fl.dto.media.FindAdvResult;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.service.media.JdMedia;
import org.rx.fl.service.media.Media;
import org.rx.fl.service.media.TbMedia;
import org.rx.util.ManualResetEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static org.rx.common.Contract.require;

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

    public List<MediaType> getMedias() {
        return NQuery.of(holder.keySet()).toList();
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

    private <T> T invoke(MediaType type, Function<Media, T> function) {
        require(type, function);

        Media media = create(type, true);
        try {
            return function.apply(media);
        } finally {
            release(media);
        }
    }

    public String findLink(String content) {
        require(content);

        for (MediaType type : getMedias()) {
            String link = invoke(type, p -> {
                return p.findLink(content);
            });
            if (!Strings.isNullOrEmpty(link)) {
                return link;
            }
        }
        return "";
    }

    public List<FindAdvResult> findAdv(String... contentArray) {
        require(contentArray);

        List<FindAdvResult> list = new ArrayList<>();
        for (String content : contentArray) {
            for (MediaType type : getMedias()) {
                invoke(type, media -> {
                    FindAdvResult result = new FindAdvResult();
                    list.add(result);
                    result.setMediaType(media.getType());
                    result.setLink(media.findLink(content));

                    if (Strings.isNullOrEmpty(result.getLink())) {
                        result.setFoundStatus(AdvFoundStatus.NoLink);
                        return;
                    }

                    result.setGoods(media.findGoods(result.getLink()));
                    if (result.getGoods() == null || Strings.isNullOrEmpty(result.getGoods().getSellerName())) {
                        result.setFoundStatus(AdvFoundStatus.NoGoods);
                        return;
                    }

                    media.login();
                    result.setShareCode(media.findAdv(result.getGoods()));
                    if (Strings.isNullOrEmpty(result.getShareCode())) {
                        result.setFoundStatus(AdvFoundStatus.NoAdv);
                        return;
                    }

                    result.setFoundStatus(AdvFoundStatus.Ok);
                });
            }
        }
        return list;
    }
}
