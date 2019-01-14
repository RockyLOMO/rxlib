package org.rx.fl.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.rx.common.App;
import org.rx.fl.dto.bot.MessageInfo;
import org.rx.fl.service.bot.WxBot;
import org.rx.fl.service.command.CommandManager;
import org.rx.fl.service.command.impl.HelpCmd;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static org.rx.common.Contract.require;

@Service
@Slf4j
public class BotService {
    @Getter
    private WxBot wxBot;
    @Resource
    private UserService userService;
    @Resource
    private HelpCmd helpCmd;
    @Resource
    private CommandManager commandManager;

    @Autowired
    public BotService(WxBot bot) {
        wxBot = bot;
        wxBot.onReceiveMessage(messageInfo -> handleMessage(messageInfo));
    }

    public String handleMessage(MessageInfo msg) {
        require(msg);

        String userId = userService.getUserId(wxBot.getType(), msg.getOpenId());
        String content = msg.isSubscribe() ? "subscribe" : msg.getContent();
        if (App.isNullOrWhiteSpace(msg.getContent())) {
            //图片之类不处理
            return helpCmd.handleMessage(userId, content).getValue();
        }
        return commandManager.handleMessage(userId, content);
    }
}
