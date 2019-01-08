package org.rx.fl.dto.repo;

import lombok.Getter;
import org.rx.Description;

public enum FeedbackStatus {
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
