package org.rx.spring;

import org.rx.exception.ExceptionLevel;
import org.rx.exception.InvalidException;

public class NotSignInException extends InvalidException {
    public NotSignInException() {
        super("Not sign in");
        super.level(ExceptionLevel.USER_OPERATION);
    }
}
