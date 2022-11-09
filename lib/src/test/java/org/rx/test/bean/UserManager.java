package org.rx.test.bean;

import org.rx.annotation.ErrorCode;
import org.rx.core.EventTarget;

public interface UserManager extends EventTarget<UserManager>, AutoCloseable {
    enum BizCode {
        @ErrorCode
        USER_NOT_FOUND,
        COMPUTE_FAIL;
    }

    default void close() {
        System.out.println("invoke disconnect");
    }

    void create(PersonBean person);

    int computeLevel(int x, int y);

    void triggerError();
}
