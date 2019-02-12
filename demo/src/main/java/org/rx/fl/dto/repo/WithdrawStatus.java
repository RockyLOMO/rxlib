package org.rx.fl.dto.repo;

import lombok.Getter;
import org.rx.annotation.Description;
import org.rx.util.NEnum;

public enum WithdrawStatus implements NEnum {
    @Description("待转")
    Wait(1),
    @Description("已转")
    Transferred(2),
    @Description("失败")
    Fail(9);

    @Getter
    private int value;

    WithdrawStatus(int value) {
        this.value = value;
    }
}
