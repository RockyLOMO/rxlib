package org.rx.beans;

import lombok.Data;

import static org.rx.common.Contract.require;

@Data
public class DataRange<T extends Comparable<T>> {
    public final T start;
    public final T end;

    public DataRange(T start, T end) {
        require(start, end);
        require(start, start.compareTo(end) <= 0);

        this.start = start;
        this.end = end;
    }

    public boolean fit(T data) {
        return start.compareTo(data) <= 0 && end.compareTo(data) >= 0;
    }
}