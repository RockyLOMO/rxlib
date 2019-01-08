package org.rx.fl.dto.media;

import lombok.Getter;
import org.rx.Description;
import org.rx.util.NEnum;

public enum OrderStatus implements NEnum {
    @Description("付款")
    Paid(1),
    @Description("成功")
    Success(2),
    @Description("结算")
    Settlement(3),
    @Description("失效")
    Invalid(-1);

    @Getter
    private int value;

    OrderStatus(int value) {
        this.value = value;
    }
}
