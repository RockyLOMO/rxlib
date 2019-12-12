package org.rx.util;

import lombok.Getter;
import org.rx.core.SystemException;

import java.util.Set;

public class BeanMapException extends SystemException {
    @Getter
    private Set<String> allMethodNames, missedMethodNames;

    BeanMapException(Exception ex) {
        super(ex);
    }

    public BeanMapException(String message, Set<String> allMethodNames, Set<String> missedMethodNames) {
        super(message);
        this.allMethodNames = allMethodNames;
        this.missedMethodNames = missedMethodNames;
    }
}
