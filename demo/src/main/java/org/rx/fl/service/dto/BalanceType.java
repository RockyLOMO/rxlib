package org.rx.fl.service.dto;

import lombok.Getter;
import org.rx.Description;

public enum BalanceType {
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
