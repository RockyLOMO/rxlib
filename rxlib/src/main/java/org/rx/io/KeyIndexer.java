package org.rx.io;

import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import java.io.Serializable;

public interface KeyIndexer<TK> extends AutoCloseable {
    @RequiredArgsConstructor
    @EqualsAndHashCode
    @ToString
    class KeyEntity<TK> implements Serializable {
        private static final long serialVersionUID = -725164142563152444L;
        final TK key;
        long logPosition;
    }

    KeyEntity<TK> newKey(TK key);

    void save(KeyEntity<TK> key);

    KeyEntity<TK> find(TK k);

    void clear();
}
