package org.rx.fl.service.command.impl;

import org.rx.fl.service.bot.Bot;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(20)
@Component
public class SubscribeCmd implements Command {
    @Override
    public boolean peek(String message) {
        return Bot.SubscribeContent.equals(message) || "我通过了你的朋友验证请求，现在我们可以开始聊天了".equals(message);
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        return HandleResult.ok("一一一一系 统 消 息一一一一\n" +
                "亲，您可算来啦～\n\n" +
                "淘宝返利教程：\nhttp://t.cn/EcS0bfI\n" +
                "京东返利教程：\nhttp://t.cn/EcSjhnd");
    }
}
