package org.rx.fl.dto.repo;

import lombok.Getter;
import org.rx.Description;
import org.rx.util.NEnum;

public enum BalanceType implements NEnum {
    @Description("收入")
    Income(1),
    @Description("支出")
    Expense(2);

    @Getter
    private int value;

    BalanceType(int value) {
        this.value = value;
    }
}
