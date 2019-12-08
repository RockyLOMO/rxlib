package org.rx.core;

import com.google.common.collect.Streams;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.rx.annotation.ErrorCode;
import org.rx.beans.Tuple;

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
public final class NQuery<T> implements Iterable<T>, Serializable {
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
    private static final Comparator NaturalOrder = Comparator.naturalOrder(), ReverseOrder = Comparator.reverseOrder();

    @ErrorCode(value = "argError", messageKeys = {"$type"})
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

        throw new SystemException(values(type.getSimpleName()), "argError");
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

    public static <T> NQuery<T> of(Iterable<T> iterable) {
        return of(iterable.iterator());
    }

    public static <T> NQuery<T> of(Iterator<T> iterator) {
        return of(toList(iterator));
    }

    public static <T> NQuery<T> of(Stream<T> stream) {
        require(stream);

        return of(stream.collect(Collectors.toList()), stream.isParallel());
    }

    public static <T> NQuery<T> of(Collection<T> set) {
        return of(set, false);
    }

    public static <T> NQuery<T> of(Collection<T> set, boolean isParallel) {
        require(set);

        return new NQuery<>(set, isParallel);
    }
    //endregion

    //region Member
    private Collection<T> current;
    private boolean isParallel;

    public Stream<T> stream() {
        return isParallel ? current.parallelStream() : current.stream();
    }

    @Override
    public Iterator<T> iterator() {
        return stream().iterator();
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

    private <TR> Stream<TR> newStream(Collection<TR> set) {
        return isParallel ? set.parallelStream() : set.stream();
    }

    private <TR> NQuery<TR> me(Collection<TR> set) {
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

    public NQuery<T> reverse() {
        return me(stream().sorted((Comparator<T>) ReverseOrder));
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
        return current.size();
    }

    public int count(Predicate<T> predicate) {
        return (int) stream().filter(predicate).count();
    }

    public T max() {
        return max(stream());
    }

    private <TR> TR max(Stream<TR> stream) {
        return stream.max((Comparator<TR>) NaturalOrder).orElse(null);
    }

    public <TR> TR max(Function<T, TR> selector) {
        return max(stream().map(selector));
    }

    public T min() {
        return min(stream());
    }

    private <TR> TR min(Stream<TR> stream) {
        return stream.min((Comparator<TR>) NaturalOrder).orElse(null);
    }

    public <TR> TR min(Function<T, TR> selector) {
        return min(stream().map(selector));
    }

    public double sum(ToDoubleFunction<T> selector) {
        return stream().mapToDouble(selector).sum();
    }

    public <TR> NQuery<TR> cast() {
        return (NQuery<TR>) this;
    }

    public <TR> NQuery<TR> ofType(Class<TR> type) {
        return where(p -> Reflects.isInstance(p, type)).select(p -> (TR) p);
    }

    public T first() {
        return stream().findFirst().get();
    }

    public T first(Predicate<T> predicate) {
        return where(predicate).first();
    }

    public T firstOrDefault() {
        return stream().findFirst().orElse(null);
    }

    public T firstOrDefault(Predicate<T> predicate) {
        return where(predicate).firstOrDefault();
    }

    public T last() {
        return Streams.findLast(stream()).get();
    }

    public T last(Predicate<T> predicate) {
        return where(predicate).last();
    }

    public T lastOrDefault() {
        return Streams.findLast(stream()).orElse(null);
    }

    public T lastOrDefault(Predicate<T> predicate) {
        return where(predicate).lastOrDefault();
    }

    @ErrorCode(messageKeys = {"$count"})
    public T single() {
        int count = count();
        if (count != 1) {
            throw new SystemException(values(count));
        }
        return first();
    }

    public T single(Predicate<T> predicate) {
        return where(predicate).single();
    }

    @ErrorCode(messageKeys = {"$count"})
    public T singleOrDefault() {
        int count = count();
        if (count > 1) {
            throw new SystemException(values(count));
        }
        return firstOrDefault();
    }

    public T singleOrDefault(Predicate<T> predicate) {
        return where(predicate).singleOrDefault();
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

    public Collection<T> asCollection() {
        return current;
    }

    public String toJoinString(String delimiter, Function<T, String> selector) {
        return String.join(delimiter, select(selector));
    }

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
            throw new InvalidOperationException("Empty Result");
        }
        T[] array = (T[]) Array.newInstance(type, result.size());
        result.toArray(array);
        return array;
    }

    public T[] toArray(Class<T> type) {
        List<T> result = toList();
        T[] array = (T[]) Array.newInstance(type, result.size());
        result.toArray(array);
        return array;
    }

    public List<T> toList() {
        List<T> result = newList();
        result.addAll(current);
        return result;
    }

    public Set<T> toSet() {
        Set<T> result = newSet();
        result.addAll(current);
        return result;
    }

    public <TK> Map<TK, T> toMap(Function<T, TK> keySelector) {
        return toMap(keySelector, p -> p);
    }

    /**
     * Collectors.toMap 会校验value为null的情况
     *
     * @param keySelector
     * @param resultSelector
     * @param <TK>
     * @param <TR>
     * @return
     */
    public <TK, TR> Map<TK, TR> toMap(Function<T, TK> keySelector, Function<T, TR> resultSelector) {
        Map<TK, TR> result = newMap();
        for (T item : current) {
            result.put(keySelector.apply(item), resultSelector.apply(item));
        }
        return result;
    }
}
