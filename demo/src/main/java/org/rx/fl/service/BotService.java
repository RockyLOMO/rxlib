package org.rx.fl.service;

import lombok.extern.slf4j.Slf4j;
import org.rx.fl.dto.bot.MessageInfo;
import org.rx.fl.service.bot.WxBot;
import org.rx.fl.service.command.CommandManager;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static org.rx.common.Contract.require;

@Service
@Slf4j
public class BotService {
    @Resource
    private UserService userService;
    @Resource
    private CommandManager commandManager;

    public BotService() {
        WxBot.Instance.onReceiveMessage(messageInfo -> handleMessage(messageInfo));
    }

    public String handleMessage(MessageInfo msg) {
        require(msg);

        String userId = userService.getUserId(WxBot.Instance.getType(), msg.getOpenId());
        String content = msg.isSubscribe() ? "subscribe" : msg.getContent();
        return commandManager.handleMessage(userId, content);
    }
}
