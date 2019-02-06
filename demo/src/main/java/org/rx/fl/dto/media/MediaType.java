package org.rx.fl.dto.media;

import lombok.Getter;
import org.rx.annotation.Description;
import org.rx.util.NEnum;

public enum MediaType implements NEnum {
    @Description("淘宝")
    Taobao(1),
    @Description("京东")
    Jd(2),
    @Description("考拉")
    Kaola(3);

    @Getter
    private int value;

    MediaType(int value) {
        this.value = value;
    }
}
