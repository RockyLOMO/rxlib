package org.rx.test.bean;

import org.rx.annotation.ErrorCode;

public interface UserCode {
    enum xCode {
        @ErrorCode(messageKeys = {"$arg"})
        argument,
        returnValue;
    }

    int add(int a, int b);

    void testError();
}
