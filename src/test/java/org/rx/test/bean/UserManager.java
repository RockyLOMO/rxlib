package org.rx.test.bean;

import org.rx.annotation.ErrorCode;
import org.rx.bean.FlagsEnum;
import org.rx.core.EventTarget;

public interface UserManager extends EventTarget<UserManager>, AutoCloseable {
    enum BizCode {
        @ErrorCode
        argument,
        returnValue;
    }

    @Override
    default FlagsEnum<EventFlags> eventFlags() {
        return EventFlags.DYNAMIC_ATTACH.flags();
    }

    default void close() {
        System.out.println("invoke default close then disconnect");
    }

    void create(PersonBean person);

    int computeInt(int x, int y);

    void triggerError();
}
