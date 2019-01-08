package org.rx.fl.service.command.impl;

import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(8)
@Component
public class SubscribeCmd implements Command {
    @Override
    public boolean peek(String message) {
        return "subscribe".equals(message);
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        return HandleResult.of("一一一一系 统 消 息一一一一\n" +
                "亲，发送给我淘宝天猫分享信息如\n\n" +
                "“【Apple/苹果 iPhone 8 Plus苹果8代5.5寸分期8p正品手机 苹果8plus】https://m.tb.cn/h.3JWcCjA 点击链接，再选择浏览器咑閞；或復·制这段描述￥x4aVbLnW5Cz￥后到淘♂寳♀”\n\n" +
                "立马查询优惠返利～");
    }
}
