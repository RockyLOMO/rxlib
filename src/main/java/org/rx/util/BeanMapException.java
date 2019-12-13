package org.rx.util;

import lombok.Getter;
import org.rx.core.SystemException;

import java.util.Set;

public class BeanMapException extends SystemException {
    @Getter
    private Set<String> missedProperties;

    public BeanMapException(String message, Set<String> missedProperties) {
        super(message);
        this.missedProperties = missedProperties;
    }
}
