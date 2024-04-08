package org.rx.bean;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.rx.core.Reflects;
import org.rx.core.Strings;

import java.io.Serializable;

import static org.rx.core.Extends.require;

@Data
@NoArgsConstructor
public class DataRange<T extends Comparable<T>> implements Serializable {
    private static final long serialVersionUID = 2698228026798507997L;

    public static <T extends Comparable<T>> DataRange<T> of(String expr, Class<T> type) {
        String[] vals = Strings.split(expr, "-", 2);
        return new DataRange<>(Reflects.changeType(vals[0], type), Reflects.changeType(vals[1], type));
    }

    public T start;
    public T end;

    public DataRange(@NonNull T start, @NonNull T end) {
        require(start, start.compareTo(end) <= 0);

        this.start = start;
        this.end = end;
    }

    public boolean has(T data) {
        return start.compareTo(data) <= 0 && end.compareTo(data) > 0;
    }
}
