package org.rx.fl.dto.media;

import lombok.Getter;
import org.rx.Description;
import org.rx.util.NEnum;

public enum MediaType implements NEnum {
    @Description("淘宝")
    Taobao(1),
    @Description("京东")
    Jd(2);

    @Getter
    private int value;

    MediaType(int value) {
        this.value = value;
    }
}
