package org.rx.fl.dto.bot;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class OpenIdInfo {
    @NotNull
    private BotType botType;
    @NotNull
    private String openId;
    private String nickname;
}
