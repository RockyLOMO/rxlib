package org.rx.fl.dto.bot;

import lombok.Data;

@Data
public class MessageInfo {
    private BotType botType;
    private boolean subscribe;
    private String openId;
    private String content;
}
