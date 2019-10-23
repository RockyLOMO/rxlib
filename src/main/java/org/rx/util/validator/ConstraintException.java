package org.rx.util.validator;

import lombok.Getter;
import org.rx.core.SystemException;

@Getter
public class ConstraintException extends SystemException {
    private String propertyName;
    private String validateMessage;

    public ConstraintException(String propertyName, String validateMessage, String message) {
        super(message);
        this.propertyName = propertyName;
        this.validateMessage = validateMessage;
    }
}
