package org.rx.fl.service.bot;

import org.rx.fl.dto.bot.BotType;
import org.rx.fl.dto.bot.MessageInfo;

import java.util.List;
import java.util.function.Function;

public interface Bot {
    String SubscribeContent = "@Subscribe";

    BotType getType();

    void onReceiveMessage(Function<MessageInfo, List<String>> event);

    void sendMessage(List<MessageInfo> messages);
}
