package org.rx.fl.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.rx.common.BotConfig;
import org.rx.common.InvalidOperationException;
import org.rx.common.NQuery;
import org.rx.fl.dto.bot.BotType;
import org.rx.fl.dto.bot.MessageInfo;
import org.rx.fl.service.bot.WxBot;
import org.rx.fl.service.bot.WxMobileBot;
import org.rx.fl.service.command.CommandManager;
import org.rx.util.validator.EnableValid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.function.Function;

@EnableValid
@Service
@Slf4j
public class BotService {
    @Getter
    private WxBot wxBot;
    @Getter
    private WxMobileBot wxMobileBot;
    @Resource
    private UserService userService;
    @Resource
    private CommandManager commandManager;

    @Autowired
    public BotService(WxBot wxBot, BotConfig config) {
        Function<MessageInfo, String> event = messageInfo -> handleMessage(messageInfo);

        this.wxBot = wxBot;
        this.wxBot.onReceiveMessage(event);

        try {
            BotConfig.WxMobileConfig mConfig = config.getWxMobile();
            wxMobileBot = new WxMobileBot(mConfig.getCapturePeriod(), mConfig.getMaxCheckMessageCount(), mConfig.getMaxCaptureMessageCount(), mConfig.getMaxScrollMessageCount());
            wxMobileBot.onReceiveMessage(event);
            wxMobileBot.start();
        } catch (InvalidOperationException e) {
            log.warn("BotService", e);
        }
    }

    public String handleMessage(@NotNull MessageInfo message) {
        String userId = userService.getUserId(message);
        return commandManager.handleMessage(userId, message.getContent());
    }

    public void pushMessages(@NotNull List<MessageInfo> pushMessages) {
        if (wxMobileBot != null) {
            for (MessageInfo message : NQuery.of(pushMessages).where(p -> p.getBotType() == BotType.Wx)) {
                wxMobileBot.sendMessage(message);
            }
        }
    }
}
