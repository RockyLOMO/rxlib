package org.rx.fl.service.command.impl;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Strings;
import lombok.SneakyThrows;
import org.rx.common.App;
import org.rx.common.MediaConfig;
import org.rx.fl.dto.media.AdvFoundStatus;
import org.rx.fl.dto.media.FindAdvResult;
import org.rx.fl.dto.media.GoodsInfo;
import org.rx.fl.service.MediaService;
import org.rx.fl.service.user.UserService;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.rx.fl.util.HttpCaller;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.rx.common.Contract.require;
import static org.rx.util.AsyncTask.TaskFactory;

@Order(1)
@Component
public class FindAdvCmd implements Command {
    @Resource
    private MediaConfig config;
    @Resource
    private MediaService mediaService;
    @Resource
    private UserService userService;
    @Resource
    private AliPayCmd aliPayCmd;

    @Override
    public boolean peek(String message) {
        require(message);
        message = message.trim();

        return !Strings.isNullOrEmpty(mediaService.findLink(message));
    }

    @SneakyThrows
    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        FindAdvResult advResult;
        if (config.isRemoteMode()) {
            Map<String, String> data = new HashMap<>();
            data.put("content", message);
            HttpCaller caller = new HttpCaller();
            String json = caller.post(String.format("http://%s/media/findAdv", config.getRemoteEndpoint()), data);
            advResult = JSONObject.parseObject(json, FindAdvResult.class);
        } else {
            advResult = mediaService.findAdv(message);
        }
        if (advResult.getFoundStatus() != AdvFoundStatus.Ok) {
            return HandleResult.ok("一一一一系 统 消 息一一一一\n" +
                    "返利失败！\n" +
                    "亲，这家没有优惠和返利哦，您也可以多看看其他家店铺，看看有没有优惠力度大一点的卖家哦，毕竟货比三家嘛～");
        }

        TaskFactory.run(() -> userService.addUserGoods(userId, advResult.getMediaType(), advResult.getGoods().getId()));
        GoodsInfo goods = advResult.getGoods();
        Function<String, Double> convert = p -> {
            if (Strings.isNullOrEmpty(p)) {
                return 0d;
            }
            return App.changeType(p.replace("￥", "")
                    .replace("¥", "").replace("元", ""), double.class);
        };
        Double rebateAmount = convert.apply(goods.getRebateAmount()),
                couponAmount = convert.apply(goods.getCouponAmount()),
                payAmount = convert.apply(goods.getPrice())
                        - rebateAmount
                        - couponAmount;
        StringBuilder reply = new StringBuilder(String.format("一一一一系 统 消 息一一一一\n" +
                        "【%s】%s\n" +
                        "------------------------------------\n" +
                        "约反      ￥%.2f\n" +
                        "优惠券  ￥%.2f\n" +
                        "付费价  ￥%.2f\n", advResult.getMediaType().toDescription(), goods.getName(),
                rebateAmount, couponAmount, payAmount));
        StringBuilder ps = new StringBuilder();
        switch (advResult.getMediaType()) {
            case Jd:
                reply.append(String.format("抢购链接: %s", advResult.getShareCode()));
                ps.append("付款时可以使用优惠券、京豆和礼品卡抵扣哦～\n");
                break;
            case Taobao:
                reply.append(String.format("复制框内整段文字，打开「手淘」即可「领取优惠券」并购买%s",
                        advResult.getShareCode()));
                ps.append("付款时可以使用优惠券、淘金币和红包抵扣哦～\n");
                break;
//        return HandleResult.of(String.format("http://taoyouhui.ml/tb.html#/%s/%s",
//                advResult.getShareCode().replace("￥", ""),
//                URLEncoder.encode(goods.getImageUrl(), "utf-8")));
        }
        ps.append("付款后超过2分钟未收到成功消息，请发送订单号绑定。");
        ps.append(String.join("", aliPayCmd.handleMessage(userId, message).getValues()));
        return HandleResult.ok(Arrays.asList(reply.toString(), ps.toString()), null);
    }
}
