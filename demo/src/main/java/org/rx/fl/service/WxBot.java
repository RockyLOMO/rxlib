package org.rx.fl.service;

import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.App;
import org.rx.NQuery;
import org.rx.bean.DateTime;
import org.rx.fl.model.MessageInfo;
import org.rx.util.ManualResetEvent;
import weixin.popular.bean.message.EventMessage;
import weixin.popular.bean.xmlmessage.XMLMessage;
import weixin.popular.bean.xmlmessage.XMLTextMessage;
import weixin.popular.support.ExpireKey;
import weixin.popular.support.expirekey.DefaultExpireKey;
import weixin.popular.util.SignatureUtil;
import weixin.popular.util.XMLConverUtil;

import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.rx.Contract.toJsonString;
import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public final class WxBot implements Bot {
    public static final WxBot Instance = new WxBot();
    private static final String token = "wangyoufan";
//    //重复通知过滤
//    private static final ExpireKey expireKey = new DefaultExpireKey();

    @Data
    private static class CacheItem {
        private final ManualResetEvent waiter;
        private final DateTime createTime;
        private String value;

        public CacheItem() {
            waiter = new ManualResetEvent(false);
            createTime = DateTime.utcNow();
        }
    }

    private static final ConcurrentHashMap<String, CacheItem> callCache = new ConcurrentHashMap<>();

    static {
        TaskFactory.schedule(() -> {
            for (String k : NQuery.of(callCache.entrySet())
                    .where(p -> DateTime.utcNow().addMinutes(-1).after(p.getValue().getCreateTime()))
                    .select(p -> p.getKey())) {
                callCache.remove(k);
                log.info("callCache remove {}", k);
            }
        }, 40 * 1000);
    }

    private Function<MessageInfo, String> event;

    private WxBot() {
    }

    @Override
    public void login() {

    }

    @Override
    public void onReceiveMessage(Function<MessageInfo, String> event) {
        this.event = event;
    }

    @Override
    public void sendMessage(String openId, String msg) {

    }

    @SneakyThrows
    public void handleCallback(HttpServletRequest request, HttpServletResponse response) {
        ServletInputStream in = request.getInputStream();
        ServletOutputStream out = response.getOutputStream();
        String signature = request.getParameter("signature");
        String timestamp = request.getParameter("timestamp");
        String nonce = request.getParameter("nonce");
        String echostr = request.getParameter("echostr");
        if (echostr != null) {
            log.info("首次请求申请验证,返回echostr");
            outWrite(out, echostr);
            return;
        }
        if (timestamp == null || nonce == null || in == null) {
            log.info("Request params is empty");
            outWrite(out, "");
            return;
        }
        //验证请求签名
        if (!signature.equals(SignatureUtil.generateEventMessageSignature(token, timestamp, nonce))) {
            log.info("Request signature is invalid");
            outWrite(out, "Request signature is invalid");
            return;
        }

        String toMsg = "";
        //转换XML
        EventMessage eventMessage = XMLConverUtil.convertToObject(EventMessage.class, in);
        String key = App.cacheKey(eventMessage.getFromUserName() + "__"
                + eventMessage.getToUserName() + "__"
                + eventMessage.getMsgId() + "__"
                + eventMessage.getCreateTime());
//        if (expireKey.exists(key)) {
//            log.info("重复通知不作处理");
//            outWrite(out, "");
//            return;
//        }
//        expireKey.add(key);
        CacheItem cacheItem = callCache.get(key);
        boolean isProduce = false;
        if (cacheItem == null) {
            synchronized (callCache) {
                if ((cacheItem = callCache.get(key)) == null) {
                    callCache.put(key, cacheItem = new CacheItem());
                    isProduce = true;
                    log.info("callCache produce {}", key);
                }
            }
        }
        if (isProduce) {
            log.info("recv: {}", toJsonString(eventMessage));
            MessageInfo messageInfo = new MessageInfo();
            messageInfo.setOpenId(eventMessage.getFromUserName());
            if ("subscribe".equalsIgnoreCase(eventMessage.getEvent())) {
                messageInfo.setSubscribe(true);
            } else if ("text".equals(eventMessage.getMsgType())) {
                messageInfo.setContent(eventMessage.getContent());
            }
            if (event != null) {
                cacheItem.setValue(toMsg = event.apply(messageInfo));
                cacheItem.waiter.set();
            }
        } else {
            log.info("callCache consumer {}", key);
            cacheItem.waiter.waitOne();
        }
        if (toMsg.isEmpty()) {
            toMsg = cacheItem.getValue();
        }

        log.info("send: {}", toMsg);
        //创建回复
        XMLMessage xmlTextMessage = new XMLTextMessage(eventMessage.getFromUserName(), eventMessage.getToUserName(), toMsg);
        xmlTextMessage.outputStreamWrite(out);
    }

    @SneakyThrows
    private void outWrite(OutputStream out, String text) {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }
}
