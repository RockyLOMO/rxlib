package org.rx.fl.service;

import com.alibaba.fastjson.JSONArray;
import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.rx.common.FlConfig;
import org.rx.fl.model.AdvNotFoundReason;
import org.rx.fl.model.MessageInfo;
import org.rx.fl.util.HttpCaller;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class BotService {
    private static final String messageFormat = "一一一一系 统 消 息一一一一\n%s";
    private static final String defaultSendMessage = "亲，发送给我淘宝天猫分享信息如“【Apple/苹果 iPhone 8 Plus苹果8代5.5寸分期8p正品手机 苹果8plus】https://m.tb.cn/h.3JWcCjA 点击链接，再选择浏览器咑閞；或復·制这段描述￥x4aVbLnW5Cz￥后到淘♂寳♀”，立马查询优惠返利～";
    private static final String defaultNoAdvMessage = "返利失败！\n" +
            "亲，这家没有优惠和返利哦，您也可以多看看其他家店铺，看看有没有优惠力度大一点的卖家哦，毕竟货比三家嘛～";

    @Resource
    private FlConfig config;
    @Resource
    private MediaService mediaService;

    public BotService() {
        WxBot.Instance.onReceiveMessage(messageInfo -> handleMessage(messageInfo));
    }

    public String handleMessage(MessageInfo msg) {
        if (msg.isSubscribe() || Strings.isNullOrEmpty(msg.getContent())) {
            return String.format(messageFormat, defaultSendMessage);
        }

        String toMsg = null, adv = null;
        if (config.isRemoteMode()) {
            Map<String, String> data = new HashMap<>();
            data.put("sourceArray", msg.getContent());
            HttpCaller caller = new HttpCaller();
            String json = caller.post(String.format("http://%s/media/findAdv", config.getRemoteEndpoint()), data);
            if (Strings.isNullOrEmpty(json)) {
                toMsg = defaultSendMessage;
            } else {
                try {
                    JSONArray array = JSONArray.parseArray(json);
                    if (array.isEmpty()) {
                        toMsg = defaultSendMessage;
                    } else {
                        adv = array.getString(0);
                    }
                } catch (Exception e) {
                    log.error("handleMessage", e);
                    toMsg = defaultSendMessage;
                }
            }
        } else {
            List<String> advResults = mediaService.findAdv(new String[]{msg.getContent()});
            if (advResults.isEmpty()) {
                toMsg = defaultSendMessage;
            } else {
                adv = advResults.get(0);
            }
        }
        if (toMsg == null && adv != null) {
            AdvNotFoundReason reason = AdvNotFoundReason.safeValueOf(adv);
            if (reason != null) {
                switch (AdvNotFoundReason.safeValueOf(adv)) {
                    case NoLink:
                        toMsg = defaultSendMessage;
                        break;
                    case NoGoods:
                        toMsg = defaultNoAdvMessage;
                        break;
                    case NoAdv:
                        toMsg = defaultNoAdvMessage;
                        break;
                    default:
                        toMsg = adv;
                        break;
                }
            } else {
                toMsg = adv;
            }
        }
        return toMsg != null && toMsg.startsWith("http") ? toMsg : String.format(messageFormat, toMsg);
    }
}
