package org.rx.bean;

import java.util.Iterator;

public interface FluentIterable<T> extends Iterable<T>, Iterator<T> {
    default Iterator<T> iterator() {
        return this;
    }
}
