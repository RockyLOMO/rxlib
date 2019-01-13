package org.rx.fl.dto.bot;

import lombok.Getter;
import org.rx.annotation.Description;
import org.rx.util.NEnum;

public enum BotType implements NEnum {
    @Description("微信公众号")
    WxService(1),
    @Description("微信")
    Wx(2);

    @Getter
    private int value;

    BotType(int value) {
        this.value = value;
    }
}
