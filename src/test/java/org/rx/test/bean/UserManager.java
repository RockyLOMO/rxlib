package org.rx.test.bean;

import org.rx.annotation.ErrorCode;
import org.rx.beans.FlagsEnum;
import org.rx.core.EventTarget;

public interface UserManager extends EventTarget<UserManager>, AutoCloseable {
    enum BizCode {
        @ErrorCode(messageKeys = {"$arg"})
        argument,
        returnValue;
    }

    @Override
    default FlagsEnum<EventFlags> eventFlags() {
        return EventFlags.DynamicAttach.flags();
    }

    default void close() {
    }

    void addUser(PersonBean person);

    int computeInt(int x, int y);

    void testError();
}
