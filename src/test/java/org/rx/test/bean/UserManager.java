package org.rx.test.bean;

import org.rx.annotation.ErrorCode;
import org.rx.common.EventTarget;

public interface UserManager extends EventTarget<UserManager> {
    enum xCode {
        @ErrorCode(messageKeys = {"$arg"})
        argument,
        returnValue;
    }

    @Override
    default boolean dynamicAttach() {
        return true;
    }

    void addUser();

    int computeInt(int x, int y);

    void testError();
}
