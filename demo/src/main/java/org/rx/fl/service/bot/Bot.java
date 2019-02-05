package org.rx.fl.service.bot;

import org.rx.fl.dto.bot.BotType;
import org.rx.fl.dto.bot.MessageInfo;

import java.util.function.Function;

public interface Bot {
    BotType getType();

    default void login() {
    }

    void onReceiveMessage(Function<MessageInfo, String> event);

    void sendMessage(String openId, String msg);
}
