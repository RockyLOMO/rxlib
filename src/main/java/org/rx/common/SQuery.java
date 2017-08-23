package org.rx.common;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by IntelliJ IDEA. User: za-wangxiaoming Date: 2017/8/17
 */
public class SQuery<T> {
    public static <T> SQuery<T> of(Stream<T> stream) {
        return new SQuery<>(stream);
    }

    private final Stream<T> current;

    private SQuery(Stream<T> stream) {
        current = stream;
    }

    public Stream<T> stream() {
        return current;
    }

    public <TI, TR> SQuery<TR> join(Collection<TI> inner, BiFunction<T, TI, Boolean> keySelector,
                                    BiFunction<T, TI, TR> resultSelector) {
        return of(current.flatMap(
                p -> inner.stream().filter(p2 -> keySelector.apply(p, p2)).map(p3 -> resultSelector.apply(p, p3))));
    }

    public <TI, TR> SQuery<TR> join(Function<T, TI> innerSelector, BiFunction<T, TI, Boolean> keySelector,
                                    BiFunction<T, TI, TR> resultSelector) {
        List<TI> inner = new ArrayList<>();
        current.forEach(t -> inner.add(innerSelector.apply(t)));
        return join(inner, keySelector, resultSelector);
    }

    public <TK> SQuery<T> orderBy(Function<T, TK> keySelector) {
        return of(current.sorted(getComparator(keySelector)));
    }

    private <TK> Comparator<T> getComparator(Function<T, TK> keySelector) {
        return (p1, p2) -> {
            TK tk = keySelector.apply(p1);
            if (!(tk instanceof Comparable)) {
                return 0;
            }
            Comparable c = (Comparable) tk;
            return c.compareTo(keySelector.apply(p2));
        };
    }

    public <TK> SQuery<T> orderByDescending(Function<T, TK> keySelector) {
        return of(current.sorted(getComparator(keySelector).reversed()));
    }

    public <TK, TR> SQuery<TR> groupBy(Function<T, TK> keySelector, Function<Tuple<TK, Stream<T>>, TR> resultSelector) {
        Map<TK, List<T>> map = new HashMap<>();
        current.forEach(t -> {
            TK key = keySelector.apply(t);
            if (map.get(key) == null) {
                map.put(key, new ArrayList<>());
            }
            map.get(key).add(t);
        });
        List<TR> result = new ArrayList<>();
        for (TK tk : map.keySet()) {
            result.add(resultSelector.apply(Tuple.of(tk, map.get(tk).stream())));
        }
        return of(result.stream());
    }

    public List<T> toList() {
        return current.collect(Collectors.toList());
    }

    public Set<T> toSet() {
        return current.collect(Collectors.toSet());
    }
}
