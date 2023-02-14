package org.rx.util;

import lombok.Getter;
import org.rx.exception.InvalidException;

import java.util.Set;

public class BeanMapException extends InvalidException {
    @Getter
    final Set<String> missedProperties;

    public BeanMapException(String message, Set<String> missedProperties) {
        super(message);
        this.missedProperties = missedProperties;
    }
}
