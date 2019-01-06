package org.rx.fl.service.dto;

import lombok.Getter;
import org.rx.Description;

public enum BalanceSourceKind {
    @Description("签到")
    CheckIn(1),
    @Description("下单")
    Order(2),
    @Description("提现")
    Withdraw(21),
    @Description("提现中")
    Withdrawing(20);

    @Getter
    private int value;

    BalanceSourceKind(int value) {
        this.value = value;
    }
}
