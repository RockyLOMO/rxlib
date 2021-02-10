package org.rx.bean;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

import static org.rx.core.App.require;

@Data
@NoArgsConstructor
public class DataRange<T extends Comparable<T>> implements Serializable {
    private static final long serialVersionUID = 2698228026798507997L;
    public T start;
    public T end;

    public DataRange(T start, T end) {
        require(start, end);
        require(start, start.compareTo(end) <= 0);

        this.start = start;
        this.end = end;
    }

    public boolean has(T data) {
        return start.compareTo(data) <= 0 && end.compareTo(data) > 0;
    }
}
