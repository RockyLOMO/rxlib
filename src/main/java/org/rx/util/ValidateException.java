package org.rx.util;

import lombok.Getter;
import org.rx.core.exception.InvalidException;

@Getter
public class ValidateException extends InvalidException {
    private String propertyName;
    private String violationMessage;

    public ValidateException(String propertyName, String violationMessage, String message) {
        super(message);
        this.propertyName = propertyName;
        this.violationMessage = violationMessage;
    }
}
