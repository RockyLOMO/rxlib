package org.rx.bean;

import org.rx.core.Constants;
import org.rx.core.Extends;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings(Constants.NON_UNCHECKED)
public class AbstractAttributes implements Extends {
    private Map attrs;

    protected <TK, TV> Map<TK, TV> initialAttrs() {
        return new ConcurrentHashMap<>();
    }

    @Override
    public <TK, TV> TV attr(TK key) {
        if (attrs == null) {
            return null;
        }
        return (TV) attrs.get(key);
    }

    @Override
    public <TK, TV> void attr(TK key, TV value) {
        if (attrs == null) {
            attrs = initialAttrs();
        }
        attrs.put(key, value);
    }
}
