package org.rx.fl.dto.bot;

import lombok.Getter;
import org.rx.annotation.Description;
import org.rx.util.NEnum;

public enum BotType implements NEnum {
    @Description("微信公众号")
    WxInterface(1);

    @Getter
    private int value;

    BotType(int value) {
        this.value = value;
    }
}
