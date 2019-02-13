package org.rx.fl.service;

import com.google.common.base.Strings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.DateTime;
import org.rx.common.BotConfig;
import org.rx.common.InvalidOperationException;
import org.rx.common.NQuery;
import org.rx.fl.dto.bot.BotType;
import org.rx.fl.dto.bot.MessageInfo;
import org.rx.fl.service.bot.WxBot;
import org.rx.fl.service.bot.WxMobileBot;
import org.rx.fl.service.command.CommandManager;
import org.rx.fl.service.command.impl.AliPayCmd;
import org.rx.util.validator.EnableValid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.validation.constraints.NotNull;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.rx.util.AsyncTask.TaskFactory;

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
    @Resource
    private AliPayCmd aliPayCmd;

    @Autowired
    public BotService(WxBot wxBot, BotConfig config) {
        Function<MessageInfo, List<String>> event = messageInfo -> handleMessage(messageInfo);

        this.wxBot = wxBot;
        this.wxBot.onReceiveMessage(event);

        try {
            BotConfig.WxMobileConfig mConfig = config.getWxMobile();
            wxMobileBot = new WxMobileBot(mConfig.getCapturePeriod(), mConfig.getMaxCheckMessageCount(), mConfig.getMaxCaptureMessageCount(), mConfig.getMaxScrollMessageCount());
            wxMobileBot.onReceiveMessage(event);
            wxMobileBot.start();

            TaskFactory.schedule(() -> {
                if (Strings.isNullOrEmpty(aliPayCmd.getSourceMessage())) {
                    return;
                }
                int hours = DateTime.now().getHours();
                switch (hours) {
                    case 8:
                    case 11:
                    case 12:
                    case 18:
                        MessageInfo message = new MessageInfo();
                        message.setBotType(BotType.Wx);
                        message.setOpenId(WxMobileBot.whiteOpenIds.first());
                        message.setContent(aliPayCmd.getSourceMessage());
                        pushMessages(Collections.singletonList(message));
                        break;
                }
            }, 60 * 60 * 1000);
        } catch (InvalidOperationException e) {
            log.warn("BotService", e);
        }
    }

    public List<String> handleMessage(@NotNull MessageInfo message) {
        String userId = userService.getUserId(message);
        return commandManager.handleMessage(userId, message.getContent());
    }

    public void pushMessages(@NotNull List<MessageInfo> messages) {
        NQuery<MessageInfo> query = NQuery.of(messages);
        if (wxMobileBot != null) {
            wxMobileBot.sendMessage(query.where(p -> p.getBotType() == BotType.Wx).toList());
        }
        wxBot.sendMessage(query.where(p -> p.getBotType() == BotType.WxService).toList());
    }
}
