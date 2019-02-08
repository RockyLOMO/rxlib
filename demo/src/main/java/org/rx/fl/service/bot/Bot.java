package org.rx.fl.service.bot;

import org.rx.fl.dto.bot.BotType;
import org.rx.fl.dto.bot.MessageInfo;
import org.rx.fl.dto.bot.OpenIdInfo;

import java.util.function.Function;

public interface Bot {
    String SubscribeContent = "@Subscribe";

    BotType getType();

    default void login() {
    }

    void onReceiveMessage(Function<MessageInfo, String> event);

    void sendMessage(MessageInfo message);
}
