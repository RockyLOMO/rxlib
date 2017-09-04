package org.rx.util;

import java.util.Set;

public class BeanMapException extends RuntimeException {
    private Set<String> allMethodNames, missedMethodNames;

    public Set<String> getAllMethodNames() {
        return allMethodNames;
    }

    public Set<String> getMissedMethodNames() {
        return missedMethodNames;
    }

    BeanMapException(Exception ex) {
        super(ex);
    }

    public BeanMapException(String message, Set<String> allMethodNames, Set<String> missedMethodNames) {
        super(message);
        this.allMethodNames = allMethodNames;
        this.missedMethodNames = missedMethodNames;
    }
}
