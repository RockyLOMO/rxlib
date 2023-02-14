package org.rx.spring;

import org.rx.exception.ExceptionLevel;
import org.rx.exception.InvalidException;

public class NotSignInException extends InvalidException {
    public NotSignInException() {
        super(ExceptionLevel.USER_OPERATION, "Not sign in");
    }
}
