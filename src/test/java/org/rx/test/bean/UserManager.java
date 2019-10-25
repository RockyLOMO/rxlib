package org.rx.test.bean;

import org.rx.annotation.ErrorCode;
import org.rx.core.EventTarget;

public interface UserManager extends EventTarget<UserManager>, AutoCloseable {
    enum BizCode {
        @ErrorCode(messageKeys = {"$arg"})
        argument,
        returnValue;
    }

    @Override
    default boolean dynamicAttach() {
        return true;
    }

    default void close() {
    }

    void addUser();

    int computeInt(int x, int y);

    void testError();
}
