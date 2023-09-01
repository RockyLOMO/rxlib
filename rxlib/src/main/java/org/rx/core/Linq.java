package org.rx.core;

import io.netty.util.internal.ThreadLocalRandom;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.rx.annotation.ErrorCode;
import org.rx.bean.$;
import org.rx.bean.Decimal;
import org.rx.exception.ApplicationException;
import org.rx.util.function.*;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.rx.bean.$.$;
import static org.rx.core.Constants.NON_RAW_TYPES;
import static org.rx.core.Constants.NON_UNCHECKED;
import static org.rx.core.Extends.*;

/**
 * https://msdn.microsoft.com/en-us/library/bb738550(v=vs.110).aspx
 */
@Slf4j
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class Linq<T> implements Iterable<T>, Serializable {
    private static final long serialVersionUID = -7167070585936243198L;
    static final Linq EMPTY = new Linq<>(Collections.emptyList(), false);

    //region staticMembers
    public static boolean tryAsIterableType(Class<?> type) {
        return Iterable.class.isAssignableFrom(type)
                || type.isArray()
                || Iterator.class.isAssignableFrom(type);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @ErrorCode
    public static <T> Iterable<T> asIterable(@NonNull Object collection, boolean throwOnFail) {
        if (collection instanceof Iterable) {
            return (Iterable<T>) collection;
        }

        Class<?> type = collection.getClass();
        if (type.isArray()) {
            int length = Array.getLength(collection);
            List<T> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                list.add((T) Array.get(collection, i));
            }
            return list;
        }

        if (collection instanceof Iterator) {
            return IteratorUtils.asIterable((Iterator<T>) collection);
        }

        if (throwOnFail) {
            throw new ApplicationException(values(type.getSimpleName()));
        }
        return null;
    }

    public static <T> Linq<T> empty() {
        return (Linq<T>) EMPTY;
    }

    public static <T> Linq<T> fromIterable(Object iterable) {
        return from(asIterable(iterable, true));
    }

    public static <T> Linq<T> from(T one) {
        if (one == null) {
            return EMPTY;
        }
        return from(Arrays.toList(one));
    }

    @SafeVarargs
    public static <T> Linq<T> from(T... array) {
        return from(Arrays.toList(array));
    }

    public static <T> Linq<T> from(Stream<T> stream) {
        if (stream == null) {
            return EMPTY;
        }
        return from(stream::iterator, stream.isParallel());
    }

    public static <T> Linq<T> from(Iterable<T> iterable) {
        return from(iterable, false);
    }

    public static <T> Linq<T> from(Iterable<T> iterable, boolean isParallel) {
        return iterable == null ? EMPTY : new Linq<>(iterable, isParallel);
    }
    //endregion

    //region Member
    private final Iterable<T> data;
    private final boolean parallel;

    public Stream<T> stream() {
        return StreamSupport.stream(data.spliterator(), parallel);
    }

    private <TR> List<TR> newList() {
        Collection<T> ts = asCollection();
        int count = ts != null ? ts.size() : 0;
        return parallel ? newConcurrentList(count, false) : new ArrayList<>(count);
    }

    private <TR> Set<TR> newSet() {
        Collection<T> ts = asCollection();
        int count = ts != null ? ts.size() : 16;
        return parallel ? Collections.synchronizedSet(new LinkedHashSet<>(count)) : new LinkedHashSet<>(count);
    }

    private <TK, TR> Map<TK, TR> newMap() {
        Collection<T> ts = asCollection();
        int count = ts != null ? ts.size() : 16;
        return parallel ? Collections.synchronizedMap(new LinkedHashMap<>(count)) : new LinkedHashMap<>(count);
    }

    private Collection<T> asCollection() {
        return data instanceof Collection ? (Collection<T>) data : null;
    }

    private <TR> Stream<TR> newStream(Iterable<TR> iterable) {
        return StreamSupport.stream(iterable.spliterator(), parallel);
    }

    private <TR> Linq<TR> me(Iterable<TR> set) {
        return from(set, parallel);
    }

    private <TR> Linq<TR> me(Stream<TR> stream) {
        return me(stream.collect(Collectors.toList()));
    }

    @SneakyThrows
    private Linq<T> me(EachFunc<T> func, String prevMethod) {
        if (parallel) {
            log.warn("Not support parallel {}", prevMethod);
        }

        Spliterator<T> spliterator = data.spliterator();
        Stream<T> r = StreamSupport.stream(new Spliterators.AbstractSpliterator<T>(spliterator.estimateSize(), spliterator.characteristics()) {
            final AtomicBoolean breaker = new AtomicBoolean();
            final AtomicInteger counter = new AtomicInteger();

            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                return spliterator.tryAdvance(p -> {
                    int flags = func.each(p, counter.getAndIncrement());
                    if ((flags & EachFunc.ACCEPT) == EachFunc.ACCEPT) {
                        action.accept(p);
                    }
                    if ((flags & EachFunc.BREAK) == EachFunc.BREAK) {
                        breaker.set(true);
                    }
                }) && !breaker.get();
            }
        }, parallel);
        return me(r);
    }

    @FunctionalInterface
    interface EachFunc<T> {
        int NONE = 0;
        int ACCEPT = 1;
        int BREAK = 1 << 1;

        int each(T item, int index);
    }
    //endregion

    @Override
    public Iterator<T> iterator() {
        return data.iterator();
    }

    @Override
    public void forEach(Consumer<? super T> action) {
        stream().forEach(action);
    }

    public void forEachOrdered(Consumer<? super T> action) {
        stream().forEachOrdered(action);
    }

    public <TR> Linq<TR> select(BiFunc<T, TR> selector) {
        return me(stream().map(selector));
    }

    public <TR> Linq<TR> select(BiFuncWithIndex<T, TR> selector) {
        AtomicInteger counter = new AtomicInteger();
        return me(stream().map(p -> selector.apply(p, counter.getAndIncrement())));
    }

    public <TR> Linq<TR> selectMany(BiFunc<T, Iterable<TR>> selector) {
        return me(stream().flatMap(p -> newStream(selector.apply(p))));
    }

    public <TR> Linq<TR> selectMany(BiFuncWithIndex<T, Iterable<TR>> selector) {
        AtomicInteger counter = new AtomicInteger();
        return me(stream().flatMap(p -> newStream(selector.apply(p, counter.getAndIncrement()))));
    }

    public Linq<T> where(PredicateFunc<T> predicate) {
        return me(stream().filter(predicate));
    }

    public Linq<T> where(PredicateFuncWithIndex<T> predicate) {
        AtomicInteger counter = new AtomicInteger();
        return me(stream().filter(p -> predicate.test(p, counter.getAndIncrement())));
    }

    public <TI, TR> Linq<TR> join(Iterable<TI> inner, BiPredicate<T, TI> keySelector, TripleFunc<T, TI, TR> resultSelector) {
        return me(stream().flatMap(p -> newStream(inner).filter(p2 -> keySelector.test(p, p2)).map(p3 -> resultSelector.apply(p, p3))));
    }

    public <TI, TR> Linq<TR> join(BiFunc<T, TI> innerSelector, BiPredicate<T, TI> keySelector, TripleFunc<T, TI, TR> resultSelector) {
        return join(stream().map(innerSelector).collect(Collectors.toList()), keySelector, resultSelector);
    }

    public <TI, TR> Linq<TR> joinMany(BiFunc<T, Iterable<TI>> innerSelector, BiPredicate<T, TI> keySelector, TripleFunc<T, TI, TR> resultSelector) {
        return join(stream().flatMap(p -> newStream(innerSelector.apply(p))).collect(Collectors.toList()), keySelector, resultSelector);
    }

    public <TI, TR> Linq<TR> leftJoin(Iterable<TI> inner, BiPredicate<T, TI> keySelector, TripleFunc<T, TI, TR> resultSelector) {
        return me(stream().flatMap(p -> {
            if (!newStream(inner).anyMatch(p2 -> keySelector.test(p, p2))) {
                return Stream.of(resultSelector.apply(p, null));
            }
            return newStream(inner).filter(p2 -> keySelector.test(p, p2)).map(p3 -> resultSelector.apply(p, p3));
        }));
    }

    public <TI, TR> Linq<TR> leftJoin(BiFunc<T, TI> innerSelector, BiPredicate<T, TI> keySelector, TripleFunc<T, TI, TR> resultSelector) {
        return leftJoin(stream().map(innerSelector).collect(Collectors.toList()), keySelector, resultSelector);
    }

    public <TI, TR> Linq<TR> leftJoinMany(BiFunc<T, Iterable<TI>> innerSelector, BiPredicate<T, TI> keySelector, TripleFunc<T, TI, TR> resultSelector) {
        return leftJoin(stream().flatMap(p -> newStream(innerSelector.apply(p))).collect(Collectors.toList()), keySelector, resultSelector);
    }

    public boolean all(PredicateFunc<T> predicate) {
        return stream().allMatch(predicate);
    }

    public boolean any() {
        return stream().findAny().isPresent();
    }

    public boolean any(PredicateFunc<T> predicate) {
        return stream().anyMatch(predicate);
    }

    public boolean contains(T item) {
        return stream().anyMatch(p -> p.equals(item));
    }

    public Linq<T> concat(Iterable<T> set) {
        return me(Stream.concat(stream(), newStream(set)));
    }

    public Linq<T> distinct() {
        return me(stream().distinct());
    }

    public Linq<T> except(Iterable<T> set) {
        return me(stream().filter(p -> !newStream(set).anyMatch(p2 -> p2.equals(p))));
    }

    public Linq<T> intersection(Iterable<T> set) {
        return me(stream().filter(p -> newStream(set).anyMatch(p2 -> p2.equals(p))));
    }

    public Linq<T> difference(Iterable<T> set) {
        return Linq.from(CollectionUtils.disjunction(this, set));
    }

    public Linq<T> union(Iterable<T> set) {
        return Linq.from(CollectionUtils.union(this, set));
    }

    public Linq<T> orderByRand() {
        return me(stream().sorted(getComparator(p -> ThreadLocalRandom.current().nextInt(0, 100), false)));
    }

    public <TK> Linq<T> orderBy(BiFunc<T, TK> keySelector) {
//        return me(stream().sorted(Comparator.nullsLast(Comparator.comparing((Function) keySelector))));
        return me(stream().sorted(getComparator(keySelector, false)));
    }

    @SuppressWarnings(NON_RAW_TYPES)
    public static <T, TK> Comparator<T> getComparator(BiFunc<T, TK> keySelector, boolean descending) {
        return (p1, p2) -> {
            Comparable a = as(keySelector.apply(p1), Comparable.class);
            Comparable b = as(keySelector.apply(p2), Comparable.class);
            boolean nullFirst = false;
            if (a == null) {
                return b == null ? 0 : (nullFirst ? -1 : 1);
            }
            if (b == null) {
                return nullFirst ? 1 : -1;
            }
            return descending ? b.compareTo(a) : a.compareTo(b);
        };
    }

    public <TK> Linq<T> orderByDescending(BiFunc<T, TK> keySelector) {
        return me(stream().sorted(getComparator(keySelector, true)));
    }

    public Linq<T> orderByMany(BiFunc<T, List<Object>> keySelector) {
        return me(stream().sorted(getComparatorMany(keySelector, false)));
    }

    @SuppressWarnings(NON_RAW_TYPES)
    public static <T> Comparator<T> getComparatorMany(BiFunc<T, List<Object>> keySelector, boolean descending) {
        return (p1, p2) -> {
            List<Object> k1s = keySelector.apply(p1), k2s = keySelector.apply(p2);
            for (int i = 0; i < k1s.size(); i++) {
                Comparable a = as(k1s.get(i), Comparable.class);
                Comparable b = as(k2s.get(i), Comparable.class);
                boolean nullFirst = false;
                if (a == null) {
                    return b == null ? 0 : (nullFirst ? -1 : 1);
                }
                if (b == null) {
                    return nullFirst ? 1 : -1;
                }
                int r = descending ? b.compareTo(a) : a.compareTo(b);
                if (r == 0) {
                    continue;
                }
                return r;
            }
            return 0;
        };
    }

    public Linq<T> orderByDescendingMany(BiFunc<T, List<Object>> keySelector) {
        return me(stream().sorted(getComparatorMany(keySelector, true)));
    }

    @SuppressWarnings(NON_UNCHECKED)
    public Linq<T> reverse() {
        try {
            return me(stream().sorted((Comparator<T>) Comparator.reverseOrder()));
        } catch (Exception e) {
            log.warn("Try reverse fail, {}", e.getMessage());
            List<T> list = toList();
            Collections.reverse(list);
            return me(list);
        }
    }

    public <TK, TR> Linq<TR> groupBy(BiFunc<T, TK> keySelector, TripleFunc<TK, Linq<T>, TR> resultSelector) {
        Map<TK, List<T>> map = stream().collect(Collectors.groupingBy(keySelector, this::newMap, Collectors.toList()));
        List<TR> result = newList();
        for (Map.Entry<TK, List<T>> entry : map.entrySet()) {
            result.add(resultSelector.apply(entry.getKey(), from(entry.getValue())));
        }
        return me(result);
    }

    public <TK, TR> Map<TK, TR> groupByIntoMap(BiFunc<T, TK> keySelector, TripleFunc<TK, Linq<T>, TR> resultSelector) {
        Map<TK, List<T>> map = stream().collect(Collectors.groupingBy(keySelector, this::newMap, Collectors.toList()));
        Map<TK, TR> result = newMap();
        for (Map.Entry<TK, List<T>> entry : map.entrySet()) {
            result.put(entry.getKey(), resultSelector.apply(entry.getKey(), Linq.from(entry.getValue())));
        }
        return result;
    }

    public <TR> Linq<TR> groupByMany(BiFunc<T, List<Object>> keySelector, TripleFunc<List<Object>, Linq<T>, TR> resultSelector) {
        Map<List<Object>, List<T>> map = stream().collect(Collectors.groupingBy(keySelector, this::newMap, Collectors.toList()));
        List<TR> result = newList();
        for (Map.Entry<List<Object>, List<T>> entry : map.entrySet()) {
            result.add(resultSelector.apply(entry.getKey(), from(entry.getValue())));
        }
        return me(result);
    }

    public Double average(ToDoubleFunction<T> selector) {
        OptionalDouble q = stream().mapToDouble(selector).average();
        return q.isPresent() ? q.getAsDouble() : null;
    }

    public int count() {
        Collection<T> ts = asCollection();
        return ts != null ? ts.size() : (int) stream().count();
    }

    public int count(PredicateFunc<T> predicate) {
        return (int) stream().filter(predicate).count();
    }

    public T max() {
        return max(stream());
    }

    @SuppressWarnings(NON_UNCHECKED)
    private <TR> TR max(Stream<TR> stream) {
        return stream.max((Comparator<TR>) Comparator.naturalOrder()).orElse(null);
    }

    public <TR> TR max(BiFunc<T, TR> selector) {
        return max(stream().map(selector));
    }

    public T min() {
        return min(stream());
    }

    @SuppressWarnings(NON_UNCHECKED)
    private <TR> TR min(Stream<TR> stream) {
        return stream.min((Comparator<TR>) Comparator.naturalOrder()).orElse(null);
    }

    public <TR> TR min(BiFunc<T, TR> selector) {
        return min(stream().map(selector));
    }

    public double sum(ToDoubleFunction<T> selector) {
        return stream().mapToDouble(selector).sum();
    }

    public Decimal sumDecimal(BiFunc<T, Decimal> selector) {
        $<Decimal> sumValue = $(Decimal.ZERO);
        stream().forEach(p -> sumValue.v = sumValue.v.add(selector.apply(p)));
        return sumValue.v;
    }

    @SuppressWarnings(NON_UNCHECKED)
    public <TR> Linq<TR> cast() {
        return (Linq<TR>) this;
    }

    @SuppressWarnings(NON_UNCHECKED)
    public <TR> Linq<TR> ofType(Class<TR> type) {
        return where(p -> p != null && Reflects.isInstance(p, type)).select(p -> (TR) p);
    }

    public T first() {
        return stream().findFirst().get();
    }

    public T first(PredicateFunc<T> predicate) {
        return stream().filter(predicate).findFirst().get();
    }

    public T firstOrDefault() {
        return firstOrDefault((T) null);
    }

    public T firstOrDefault(T defaultValue) {
        return stream().findFirst().orElse(defaultValue);
    }

    public T firstOrDefault(Func<T> defaultValue) {
        return stream().findFirst().orElseGet(defaultValue);
    }

    public T firstOrDefault(PredicateFunc<T> predicate) {
        return stream().filter(predicate).findFirst().orElse(null);
    }

    public T last() {
        T value = lastOrDefault();
        if (value == null) {
            throw new NoSuchElementException("No value present");
        }
        return value;
    }

    public T last(PredicateFunc<T> predicate) {
        return where(predicate).last();
    }

    public T lastOrDefault() {
        T value = null;
        if (data instanceof List) {
            List<T> list = (List<T>) data;
            value = !list.isEmpty() ? list.get(list.size() - 1) : null;
        } else {
            for (T datum : data) {
                value = datum;
            }
        }
        return value;
    }

    public T lastOrDefault(T defaultValue) {
        return ifNull(lastOrDefault(), defaultValue);
    }

    public T lastOrDefault(PredicateFunc<T> predicate) {
        return where(predicate).lastOrDefault();
    }

    public T single() {
        return single(null);
    }

    @ErrorCode
    public T single(PredicateFunc<T> predicate) {
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
    public T singleOrDefault(PredicateFunc<T> predicate) {
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

    public Linq<T> skip(int count) {
        return me(stream().skip(count));
    }

    public Linq<T> skipWhile(PredicateFunc<T> predicate) {
        return skipWhile((p, i) -> predicate.invoke(p));
    }

    public Linq<T> skipWhile(PredicateFuncWithIndex<T> predicate) {
        AtomicBoolean doAccept = new AtomicBoolean();
        return me((p, i) -> {
            int flags = EachFunc.NONE;
            if (doAccept.get()) {
                flags |= EachFunc.ACCEPT;
                return flags;
            }
            if (!predicate.test(p, i)) {
                doAccept.set(true);
                flags |= EachFunc.ACCEPT;
            }
            return flags;
        }, "skipWhile");
    }

    public Linq<T> take(int count) {
        return me(stream().limit(count));
    }

    public Linq<T> takeWhile(PredicateFunc<T> predicate) {
        return takeWhile((p, i) -> predicate.invoke(p));
    }

    public Linq<T> takeWhile(PredicateFuncWithIndex<T> predicate) {
        return me((p, i) -> {
            int flags = EachFunc.NONE;
            if (!predicate.test(p, i)) {
                flags |= EachFunc.BREAK;
                return flags;
            }
            flags |= EachFunc.ACCEPT;
            return flags;
        }, "takeWhile");
    }

    public String toJoinString(String delimiter) {
        return String.join(delimiter, this.cast());
    }

    public String toJoinString(String delimiter, BiFunc<T, String> selector) {
        return String.join(delimiter, select(selector));
    }

    @SuppressWarnings(NON_UNCHECKED)
    public T[] toArray() {
        List<T> result = toList();
        Class<?> type = null;
        for (T t : result) {
            if (t == null) {
                continue;
            }
            if (type == null) {
                type = t.getClass();
                continue;
            }
            if (type != t.getClass()) {
                type = null;
                break;
            }
        }
        if (type == null) {
            type = Object.class;
        }
        T[] array = (T[]) Array.newInstance(type, result.size());
        result.toArray(array);
        return array;
    }

    @SuppressWarnings(NON_UNCHECKED)
    public T[] toArray(Class<T> type) {
        List<T> result = toList();
        T[] array = (T[]) Array.newInstance(type, result.size());
        result.toArray(array);
        return array;
    }

    public List<T> toList() {
        if (!parallel && data instanceof List) {
            return (List<T>) data;
        }
        List<T> result = newList();
        for (T item : data) {
            result.add(item);
        }
        return result;
    }

    public Set<T> toSet() {
        if (!parallel && data instanceof Set) {
            return (Set<T>) data;
        }
        Set<T> result = newSet();
        for (T item : data) {
            result.add(item);
        }
        return result;
    }

    public <TK, TR> Map<TK, TR> toMap() {
        Map<TK, TR> result = newMap();
        for (Map.Entry<TK, TR> entry : this.<Map.Entry<TK, TR>>cast()) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public <TK> Map<TK, T> toMap(BiFunc<T, TK> keySelector) {
        return toMap(keySelector, p -> p);
    }

    //Collectors.toMap() will throw exception if value is null
    @SneakyThrows
    public <TK, TR> Map<TK, TR> toMap(BiFunc<T, TK> keySelector, BiFunc<T, TR> resultSelector) {
        Map<TK, TR> result = newMap();
        stream().forEach(item -> result.put(keySelector.apply(item), resultSelector.apply(item)));
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Linq<?> linq = (Linq<?>) o;
        return parallel == linq.parallel && Objects.equals(data, linq.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(data, parallel);
    }
}
