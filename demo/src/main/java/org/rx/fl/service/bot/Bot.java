package org.rx.fl.service.bot;

import org.rx.fl.dto.bot.BotType;
import org.rx.fl.dto.bot.MessageInfo;

import java.util.List;
import java.util.function.Function;

public interface Bot {
    String SubscribeContent = "@Subscribe";

    BotType getType();

    default void login() {
    }

    void onReceiveMessage(Function<MessageInfo, List<String>> event);

    void sendMessage(MessageInfo message);
}
