package org.rx.fl.service;

import org.rx.fl.dto.MessageInfo;

import java.util.function.Function;

public interface Bot {
    void login();

    void onReceiveMessage(Function<MessageInfo, String> event);

    void sendMessage(String openId, String msg);
}
