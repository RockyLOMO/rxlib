package org.rx.fl.dto.bot;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class MessageInfo {
    @NotNull
    private BotType botType;
    private boolean subscribe;
    @NotNull
    private String openId;
    private String userName;
    private String content;
}
