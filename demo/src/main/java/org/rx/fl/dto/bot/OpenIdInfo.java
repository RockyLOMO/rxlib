package org.rx.fl.dto.bot;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
public class OpenIdInfo implements Serializable {
    @NotNull
    private BotType botType;
    @NotNull
    private String openId;
    private String nickname;
}
