package org.rx.util;

import lombok.Getter;
import org.rx.exception.ExceptionLevel;
import org.rx.exception.InvalidException;

@Getter
public class ValidateException extends InvalidException {
    final String propertyPath;
    final String violationMessage;

    public ValidateException(String propertyName, String violationMessage, String message) {
        super(ExceptionLevel.USER_OPERATION, message);
        this.propertyPath = propertyName;
        this.violationMessage = violationMessage;
    }
}
