package org.rx.core;

import java.util.Map;

public interface Attributes {
    default <T> T attrOne() {
        return attr(this);
    }

    default <T> T attrOne(T v) {
        return attr(this, v);
    }

    default <TK, TV> TV attr(TK k) {
        return Container.<TK, TV>weakMap().get(k);
    }

    default <TK, TV> TV attr(TK k, TV v) {
        Map<TK, TV> map = Container.weakMap();
        if (v == null) {
            TV old = map.get(k);
            map.remove(k);
            return old;
        }
        return map.put(k, v);
    }
}
