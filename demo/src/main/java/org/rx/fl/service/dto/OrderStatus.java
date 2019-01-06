package org.rx.fl.service.dto;

import lombok.Getter;
import org.rx.Description;

public enum OrderStatus {
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
