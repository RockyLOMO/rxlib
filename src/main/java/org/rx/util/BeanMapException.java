package org.rx.util;

import lombok.Getter;
import org.rx.core.exception.InvalidException;

import java.util.Set;

public class BeanMapException extends InvalidException {
    @Getter
    private Set<String> missedProperties;

    public BeanMapException(String message, Set<String> missedProperties) {
        super(message);
        this.missedProperties = missedProperties;
    }
}
