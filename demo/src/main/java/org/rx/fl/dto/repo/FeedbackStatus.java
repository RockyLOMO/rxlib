package org.rx.fl.dto.repo;

import lombok.Getter;
import org.rx.annotation.Description;
import org.rx.util.NEnum;

public enum FeedbackStatus implements NEnum {
    @Description("等待处理")
    WaitReply(1),
    @Description("已处理")
    Replied(2);

    @Getter
    private int value;

    FeedbackStatus(int value) {
        this.value = value;
    }
}
