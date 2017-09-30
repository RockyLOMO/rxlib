package org.rx.test.bean;

import org.rx.ErrorCode;

public interface UserCode {
    enum xCode {
        @ErrorCode(messageKeys = { "$arg" })
        argument,
        returnValue;
    }
}
