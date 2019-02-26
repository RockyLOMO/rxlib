package org.rx.fl.service.command.impl;

import org.rx.common.MediaConfig;
import org.rx.fl.service.bot.Bot;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.rx.fl.service.user.UserService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Order(20)
@Component
public class SubscribeCmd implements Command {
    @Resource
    private MediaConfig mediaConfig;
    @Resource
    private UserService userService;
    @Resource
    private HelpCmd helpCmd;

    @Override
    public boolean peek(String message) {
        return Bot.SubscribeContent.equals(message) || "我通过了你的朋友验证请求，现在我们可以开始聊天了".equals(message);
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        if (userService.isNoob(userId)) {
//            return helpCmd.handleMessage(userId, message);
            return HandleResult.ok("");
        }

        return HandleResult.ok(String.format("一一一一系 统 消 息一一一一\n" +
                "亲，您可算来啦～\n\n" +
                "淘宝返利教程：\n%s\n" +
                "京东返利教程：\n%s", mediaConfig.getTaobao().getGuideUrl(), mediaConfig.getJd().getGuideUrl()));
    }
}
