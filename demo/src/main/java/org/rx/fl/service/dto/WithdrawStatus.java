package org.rx.fl.service.dto;

import lombok.Getter;
import org.rx.Description;

public enum WithdrawStatus {
    @Description("待转")
    Wait(1),
    @Description("已转")
    Transferred(2);

    @Getter
    private int value;

    WithdrawStatus(int value) {
        this.value = value;
    }
}
