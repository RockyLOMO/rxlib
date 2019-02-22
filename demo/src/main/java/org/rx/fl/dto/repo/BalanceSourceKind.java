package org.rx.fl.dto.repo;

import lombok.Getter;
import org.rx.annotation.Description;
import org.rx.util.NEnum;

public enum BalanceSourceKind implements NEnum {
    @Description("签到")
    CheckIn(1),
    @Description("下单")
    Order(10),
    @Description("失效订单")
    InvalidOrder(12),
    @Description("佣金")
    Commission(13),
    @Description("失效佣金")
    InvalidCommission(14),
    @Description("提现")
    Withdraw(20),
    @Description("人工校正")
    Correct(100);

    @Getter
    private int value;

    BalanceSourceKind(int value) {
        this.value = value;
    }
}
