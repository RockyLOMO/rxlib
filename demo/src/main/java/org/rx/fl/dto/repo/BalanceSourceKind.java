package org.rx.fl.dto.repo;

import lombok.Getter;
import org.rx.Description;

public enum BalanceSourceKind {
    @Description("签到")
    CheckIn(1),
    @Description("下单")
    Order(10),
    @Description("绑定订单")
    RebindOrder(11),
    @Description("提现")
    Withdraw(20);

    @Getter
    private int value;

    BalanceSourceKind(int value) {
        this.value = value;
    }
}
