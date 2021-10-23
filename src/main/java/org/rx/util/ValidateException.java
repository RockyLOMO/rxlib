package org.rx.util;

import lombok.Getter;
import org.rx.exception.ExceptionLevel;
import org.rx.exception.InvalidException;

@Getter
public class ValidateException extends InvalidException {
    private String propertyName;
    private String violationMessage;

    public ValidateException(String propertyName, String violationMessage, String message) {
        super(message);
        super.level(ExceptionLevel.USER_OPERATION);
        this.propertyName = propertyName;
        this.violationMessage = violationMessage;
    }
}
