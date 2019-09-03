package org.rx.util.validator;

import org.rx.core.SystemException;

public class ConstraintException extends SystemException {
    private String propertyName;
    private String validateMessage;

    public String getPropertyName() {
        return propertyName;
    }

    public String getValidateMessage() {
        return validateMessage;
    }

    public ConstraintException(String propertyName, String validateMessage, String message) {
        super(message);
        this.propertyName = propertyName;
        this.validateMessage = validateMessage;
    }
}
