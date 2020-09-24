package org.rx.core;

import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.rx.annotation.ErrorCode;
import org.rx.bean.Tuple;
import org.rx.core.exception.ApplicationException;
import org.rx.core.exception.InvalidException;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.*;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.rx.core.Contract.*;

/**
 * https://msdn.microsoft.com/en-us/library/bb738550(v=vs.110).aspx
 *
 * @param <T>
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class NQuery<T> implements Iterable<T>, Serializable, Cloneable {
    //region nestedTypes
    @FunctionalInterface
    public interface IndexSelector<T, TR> {
        TR apply(T t, int index);
    }

    @FunctionalInterface
    public interface IndexPredicate<T> {
        boolean test(T t, int index);

        default IndexPredicate<T> negate() {
            return (t, i) -> !test(t, i);
        }
    }
    //endregion

    //region staticMembers
    @SuppressWarnings(NON_WARNING)
    @ErrorCode("argError")
    public static <T> List<T> asList(Object arrayOrIterable) {
        require(arrayOrIterable);

        Class type = arrayOrIterable.getClass();
        if (type.isArray()) {
            int length = Array.getLength(arrayOrIterable);
            List<T> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                Object item = Array.get(arrayOrIterable, i);
                list.add((T) item);
            }
            return list;
        }

        Iterable<T> iterable;
        if ((iterable = as(arrayOrIterable, Iterable.class)) != null) {
            return toList(iterable);
        }

        throw new ApplicationException(values(type.getSimpleName()), "argError");
    }

    public static <T> List<T> toList(Iterable<T> iterable) {
        return IterableUtils.toList(iterable);
    }

    public static <T> List<T> toList(Iterator<T> iterator) {
        return IteratorUtils.toList(iterator);
    }

    public static <T> NQuery<T> of(T one) {
        return of(Arrays.toList(one));
    }

    @SafeVarargs
    public static <T> NQuery<T> of(T... set) {
        return of(Arrays.toList(set));
    }

    public static <T> NQuery<T> of(Stream<T> stream) {
        require(stream);

        return of(stream::iterator, stream.isParallel());
    }

    public static <T> NQuery<T> of(Iterator<T> iterator) {
        require(iterator);

        return of(() -> iterator);
    }

    public static <T> NQuery<T> of(Iterable<T> iterable) {
        return of(iterable, false);
    }

    public static <T> NQuery<T> of(Iterable<T> iterable, boolean isParallel) {
        require(iterable);

        return new NQuery<>(iterable, isParallel);
    }
    //endregion

    //region Member
    private Iterable<T> iterable;
    private boolean isParallel;

    @SneakyThrows
    public NQuery<T> clone() {
        return (NQuery<T>) super.clone();
    }

    public Stream<T> stream() {
        return StreamSupport.stream(iterable.spliterator(), isParallel);
    }

    @Override
    public Iterator<T> iterator() {
        return iterable.iterator();
    }

    private <TR> List<TR> newList() {
        int count = count();
        return isParallel ? new Vector<>(count) : new ArrayList<>(count);//CopyOnWriteArrayList 写性能差
    }

    private <TR> Set<TR> newSet() {
        int count = count();
        return isParallel ? Collections.synchronizedSet(new LinkedHashSet<>(count)) : new LinkedHashSet<>(count);
    }

    private <TK, TR> Map<TK, TR> newMap() {
        int count = count();
        return isParallel ? Collections.synchronizedMap(new LinkedHashMap<>(count)) : new LinkedHashMap<>(count);
    }

    private <TR> Stream<TR> newStream(Iterable<TR> iterable) {
        return StreamSupport.stream(iterable.spliterator(), isParallel);
    }

    private <TR> NQuery<TR> me(Iterable<TR> set) {
        return of(set, isParallel);
    }

    private <TR> NQuery<TR> me(Stream<TR> stream) {
        return me(stream.collect(Collectors.toList()));
    }

    private NQuery<T> me(EachFunc<T> func) {
        return me(stream(), func);
    }

    private <TR> NQuery<TR> me(Stream<TR> stream, EachFunc<TR> func) {
        boolean isParallel = stream.isParallel();
        Spliterator<TR> spliterator = stream.spliterator();
        return me(StreamSupport.stream(new Spliterators.AbstractSpliterator<TR>(spliterator.estimateSize(), spliterator.characteristics()) {
            AtomicBoolean breaker = new AtomicBoolean();
            AtomicInteger counter = new AtomicInteger();

            @SuppressWarnings(NON_WARNING)
            @Override
            public boolean tryAdvance(Consumer action) {
                return spliterator.tryAdvance(p -> {
                    int flags = func.each(p, counter.getAndIncrement());
                    if ((flags & EachFunc.Accept) == EachFunc.Accept) {
                        action.accept(p);
                    }
                    if ((flags & EachFunc.Break) == EachFunc.Break) {
                        breaker.set(true);
                    }
                }) && !breaker.get();
            }
        }, isParallel));
    }

    @FunctionalInterface
    private interface EachFunc<T> {
        int None = 0;
        int Accept = 1;
        int Break = 1 << 1;
        int All = Accept | Break;

        int each(T t, int index);
    }
    //endregion

    public NQuery<T> each(IndexPredicate<T> func) {
        Iterator<T> tor = this.iterator();
        int i = 0;
        while (tor.hasNext()) {
            if (!func.test(tor.next(), i++)) {
                break;
            }
        }
        return this;
    }

    public <TR> NQuery<TR> select(Function<T, TR> selector) {
        return me(stream().map(selector));
    }

    public <TR> NQuery<TR> select(IndexSelector<T, TR> selector) {
        AtomicInteger counter = new AtomicInteger();
        return me(stream().map(p -> selector.apply(p, counter.getAndIncrement())));
    }

    public <TR> NQuery<TR> selectMany(Function<T, Collection<TR>> selector) {
        return me(stream().flatMap(p -> newStream(selector.apply(p))));
    }

    public <TR> NQuery<TR> selectMany(IndexSelector<T, Collection<TR>> selector) {
        AtomicInteger counter = new AtomicInteger();
        return me(stream().flatMap(p -> newStream(selector.apply(p, counter.getAndIncrement()))));
    }

    public NQuery<T> where(Predicate<T> predicate) {
        return me(stream().filter(predicate));
    }

    public NQuery<T> where(IndexPredicate<T> predicate) {
        AtomicInteger counter = new AtomicInteger();
        return me(stream().filter(p -> predicate.test(p, counter.getAndIncrement())));
    }

    public <TI, TR> NQuery<TR> join(Collection<TI> inner, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return me(stream().flatMap(p -> newStream(inner).filter(p2 -> keySelector.test(p, p2)).map(p3 -> resultSelector.apply(p, p3))));
    }

    public <TI, TR> NQuery<TR> join(Function<T, TI> innerSelector, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return join(stream().map(innerSelector).collect(Collectors.toList()), keySelector, resultSelector);
    }

    public <TI, TR> NQuery<TR> joinMany(Function<T, Collection<TI>> innerSelector, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return join(stream().flatMap(p -> newStream(innerSelector.apply(p))).collect(Collectors.toList()), keySelector, resultSelector);
    }

    @SuppressWarnings(NON_WARNING)
    public <TI, TR> NQuery<TR> leftJoin(Collection<TI> inner, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return me(stream().flatMap(p -> {
            if (!newStream(inner).anyMatch(p2 -> keySelector.test(p, p2))) {
                return Stream.of(resultSelector.apply(p, null));
            }
            return newStream(inner).filter(p2 -> keySelector.test(p, p2)).map(p3 -> resultSelector.apply(p, p3));
        }));
    }

    public <TI, TR> NQuery<TR> leftJoin(Function<T, TI> innerSelector, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return leftJoin(stream().map(innerSelector).collect(Collectors.toList()), keySelector, resultSelector);
    }

    public <TI, TR> NQuery<TR> leftJoinMany(Function<T, Collection<TI>> innerSelector, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return leftJoin(stream().flatMap(p -> newStream(innerSelector.apply(p))).collect(Collectors.toList()), keySelector, resultSelector);
    }

    public boolean all(Predicate<T> predicate) {
        return stream().allMatch(predicate);
    }

    public boolean any() {
        return stream().findAny().isPresent();
    }

    public boolean any(Predicate<T> predicate) {
        return stream().anyMatch(predicate);
    }

    public boolean contains(T item) {
        return stream().anyMatch(p -> p.equals(item));
    }

    public NQuery<T> concat(Iterable<T> set) {
        return concat(toList(set));
    }

    public NQuery<T> concat(Collection<T> set) {
        return me(Stream.concat(stream(), newStream(set)));
    }

    public NQuery<T> distinct() {
        return me(stream().distinct());
    }

    public NQuery<T> except(Iterable<T> set) {
        return except(toList(set));
    }

    @SuppressWarnings(NON_WARNING)
    public NQuery<T> except(Collection<T> set) {
        return me(stream().filter(p -> !newStream(set).anyMatch(p2 -> p2.equals(p))));
    }

    public NQuery<T> intersect(Iterable<T> set) {
        return intersect(toList(set));
    }

    public NQuery<T> intersect(Collection<T> set) {
        return me(stream().filter(p -> newStream(set).anyMatch(p2 -> p2.equals(p))));
    }

    public NQuery<T> union(Iterable<T> set) {
        return union(toList(set));
    }

    public NQuery<T> union(Collection<T> set) {
        return concat(set);
    }

    public <TK> NQuery<T> orderBy(Function<T, TK> keySelector) {
        return me(stream().sorted(getComparator(keySelector)));
    }

    @SuppressWarnings(NON_WARNING)
    public static <T, TK> Comparator<T> getComparator(Function<T, TK> keySelector) {
        return (p1, p2) -> {
            Comparable c1 = as(keySelector.apply(p1), Comparable.class);
            Comparable c2 = as(keySelector.apply(p2), Comparable.class);
            if (c1 == null || c2 == null) {
                return 0;
            }
            return c1.compareTo(c2);
        };
    }

    public <TK> NQuery<T> orderByDescending(Function<T, TK> keySelector) {
        return me(stream().sorted(getComparator(keySelector).reversed()));
    }

    public NQuery<T> orderByMany(Function<T, Object[]> keySelector) {
        return me(stream().sorted(getComparatorMany(keySelector)));
    }

    @SuppressWarnings(NON_WARNING)
    public static <T> Comparator<T> getComparatorMany(Function<T, Object[]> keySelector) {
        return (p1, p2) -> {
            Object[] k1s = keySelector.apply(p1);
            Object[] k2s = keySelector.apply(p2);
            for (int i = 0; i < k1s.length; i++) {
                Comparable c1 = as(k1s[i], Comparable.class);
                Comparable c2 = as(k2s[i], Comparable.class);
                if (c1 == null || c2 == null) {
                    continue;
                }
                int r = c1.compareTo(c2);
                if (r == 0) {
                    continue;
                }
                return r;
            }
            return 0;
        };
    }

    public NQuery<T> orderByDescendingMany(Function<T, Object[]> keySelector) {
        return me(stream().sorted(getComparatorMany(keySelector).reversed()));
    }

    @SuppressWarnings(NON_WARNING)
    public NQuery<T> reverse() {
        return me(stream().sorted((Comparator<T>) Comparator.reverseOrder()));
    }

    public <TK, TR> NQuery<TR> groupBy(Function<T, TK> keySelector, BiFunction<TK, NQuery<T>, TR> resultSelector) {
        Map<TK, List<T>> map = newMap();
        stream().forEach(t -> map.computeIfAbsent(keySelector.apply(t), p -> newList()).add(t));
        List<TR> result = newList();
        for (Map.Entry<TK, List<T>> entry : map.entrySet()) {
            result.add(resultSelector.apply(entry.getKey(), of(entry.getValue())));
        }
        return me(result);
    }

    public <TR> NQuery<TR> groupByMany(Function<T, Object[]> keySelector, BiFunction<Object[], NQuery<T>, TR> resultSelector) {
        Map<String, Tuple<Object[], List<T>>> map = newMap();
        stream().forEach(t -> {
            Object[] ks = keySelector.apply(t);
            map.computeIfAbsent(toJsonString(ks), p -> Tuple.of(ks, newList())).right.add(t);
        });
        List<TR> result = newList();
        for (Tuple<Object[], List<T>> entry : map.values()) {
            result.add(resultSelector.apply(entry.left, of(entry.right)));
        }
        return me(result);
    }

    public Double average(ToDoubleFunction<T> selector) {
        OptionalDouble q = stream().mapToDouble(selector).average();
        return q.isPresent() ? q.getAsDouble() : null;
    }

    public int count() {
        return (int) stream().count();
    }

    public int count(Predicate<T> predicate) {
        return (int) stream().filter(predicate).count();
    }

    public T max() {
        return max(stream());
    }

    @SuppressWarnings(NON_WARNING)
    private <TR> TR max(Stream<TR> stream) {
        return stream.max((Comparator<TR>) Comparator.naturalOrder()).orElse(null);
    }

    public <TR> TR max(Function<T, TR> selector) {
        return max(stream().map(selector));
    }

    public T min() {
        return min(stream());
    }

    @SuppressWarnings(NON_WARNING)
    private <TR> TR min(Stream<TR> stream) {
        return stream.min((Comparator<TR>) Comparator.naturalOrder()).orElse(null);
    }

    public <TR> TR min(Function<T, TR> selector) {
        return min(stream().map(selector));
    }

    public double sum(ToDoubleFunction<T> selector) {
        return stream().mapToDouble(selector).sum();
    }

    @SuppressWarnings(NON_WARNING)
    public <TR> NQuery<TR> cast() {
        return (NQuery<TR>) this;
    }

    @SuppressWarnings(NON_WARNING)
    public <TR> NQuery<TR> ofType(Class<TR> type) {
        return where(p -> Reflects.isInstance(p, type)).select(p -> (TR) p);
    }

    @SuppressWarnings(NON_WARNING)
    public T first() {
        return stream().findFirst().get();
    }

    public T first(Predicate<T> predicate) {
        return stream().filter(predicate).findFirst().get();
    }

    public T firstOrDefault() {
        return firstOrDefault((T) null);
    }

    public T firstOrDefault(T defaultValue) {
        return stream().findFirst().orElse(defaultValue);
    }

    public T firstOrDefault(Predicate<T> predicate) {
        return stream().filter(predicate).findFirst().orElse(null);
    }

    @SuppressWarnings(NON_WARNING)
    public T last() {
        return Streams.findLast(stream()).get();
    }

    public T last(Predicate<T> predicate) {
        return Streams.findLast(stream().filter(predicate)).get();
    }

    public T lastOrDefault() {
        return lastOrDefault((T) null);
    }

    public T lastOrDefault(T defaultValue) {
        return Streams.findLast(stream()).orElse(defaultValue);
    }

    public T lastOrDefault(Predicate<T> predicate) {
        return Streams.findLast(stream().filter(predicate)).orElse(null);
    }

    public T single() {
        return single(null);
    }

    @ErrorCode
    public T single(Predicate<T> predicate) {
        Stream<T> stream = stream();
        if (predicate != null) {
            stream = stream.filter(predicate);
        }
        List<T> list = stream.limit(2).collect(Collectors.toList());
        if (list.size() != 1) {
            throw new ApplicationException(values(list.size()));
        }
        return list.get(0);
    }

    public T singleOrDefault() {
        return singleOrDefault(null);
    }

    @ErrorCode
    public T singleOrDefault(Predicate<T> predicate) {
        Stream<T> stream = stream();
        if (predicate != null) {
            stream = stream.filter(predicate);
        }
        List<T> list = stream.limit(2).collect(Collectors.toList());
        if (list.size() > 1) {
            throw new ApplicationException(values(list.size()));
        }
        return list.isEmpty() ? null : list.get(0);
    }

    public NQuery<T> skip(int count) {
        return me(stream().skip(count));
    }

    public NQuery<T> skipWhile(Predicate<T> predicate) {
        return skipWhile((p, i) -> predicate.test(p));
    }

    public NQuery<T> skipWhile(IndexPredicate<T> predicate) {
        AtomicBoolean doAccept = new AtomicBoolean();
        return me((p, i) -> {
            int flags = EachFunc.None;
            if (doAccept.get()) {
                flags |= EachFunc.Accept;
                return flags;
            }
            if (!predicate.test(p, i)) {
                doAccept.set(true);
                flags |= EachFunc.Accept;
            }
            return flags;
        });
    }

    public NQuery<T> take(int count) {
        return me(stream().limit(count));
    }

    public NQuery<T> takeWhile(Predicate<T> predicate) {
        return takeWhile((p, i) -> predicate.test(p));
    }

    public NQuery<T> takeWhile(IndexPredicate<T> predicate) {
        return me((p, i) -> {
            int flags = EachFunc.None;
            if (!predicate.test(p, i)) {
                flags |= EachFunc.Break;
                return flags;
            }
            flags |= EachFunc.Accept;
            return flags;
        });
    }

    public String toJoinString(String delimiter, Function<T, String> selector) {
        return String.join(delimiter, select(selector));
    }

    @SuppressWarnings(NON_WARNING)
    public T[] toArray() {
        List<T> result = toList();
        Class type = null;
        for (T t : result) {
            if (t == null) {
                continue;
            }
            type = t.getClass();
            break;
        }
        if (type == null) {
            throw new InvalidException("Empty Result");
        }
        T[] array = (T[]) Array.newInstance(type, result.size());
        result.toArray(array);
        return array;
    }

    @SuppressWarnings(NON_WARNING)
    public T[] toArray(Class<T> type) {
        List<T> result = toList();
        T[] array = (T[]) Array.newInstance(type, result.size());
        result.toArray(array);
        return array;
    }

    public List<T> toList() {
        List<T> result = newList();
        Iterables.addAll(result, iterable);
        return result;
    }

    public Set<T> toSet() {
        Set<T> result = newSet();
        Iterables.addAll(result, iterable);
        return result;
    }

    public <TK> Map<TK, T> toMap(Function<T, TK> keySelector) {
        return toMap(keySelector, p -> p);
    }

    //Collectors.toMap 会校验value为null的情况
    public <TK, TR> Map<TK, TR> toMap(Function<T, TK> keySelector, Function<T, TR> resultSelector) {
        Map<TK, TR> result = newMap();
        for (T item : iterable) {
            result.put(keySelector.apply(item), resultSelector.apply(item));
        }
        return result;
    }
}
