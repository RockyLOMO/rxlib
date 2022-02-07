package org.rx.bean;

import java.util.Iterator;

public abstract class FluentIterable<T> implements Iterable<T>, Iterator<T> {
    @Override
    public Iterator<T> iterator() {
        return this;
    }
}
