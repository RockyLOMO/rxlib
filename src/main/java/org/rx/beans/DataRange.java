package org.rx.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

import static org.rx.core.Contract.require;

@Data
@NoArgsConstructor
public class DataRange<T extends Comparable<T>> implements Serializable {
    public T start;
    public T end;

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
