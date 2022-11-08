package org.rx.core;

import io.netty.util.internal.ThreadLocalRandom;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.rx.annotation.ErrorCode;
import org.rx.bean.$;
import org.rx.bean.Decimal;
import org.rx.exception.ApplicationException;
import org.rx.exception.InvalidException;
import org.rx.util.function.*;

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

    //region staticMembers
    public static boolean canBeCollection(Class<?> type) {
        return Iterable.class.isAssignableFrom(type)
                || type.isArray()
                || Iterator.class.isAssignableFrom(type);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @ErrorCode
    public static <T> List<T> asList(@NonNull Object collection, boolean throwOnFail) {
        Iterable<T> iterable;
        if ((iterable = as(collection, Iterable.class)) != null) {
            return IterableUtils.toList(iterable);
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

        Iterator<T> iterator;
        if ((iterator = as(collection, Iterator.class)) != null) {
            return IteratorUtils.toList(iterator);
        }

        if (throwOnFail) {
            throw new ApplicationException(values(type.getSimpleName()));
        }
        ArrayList<T> list = new ArrayList<>();
        list.add((T) collection);
        return list;
    }

    public static <T> Linq<T> fromCollection(Object collection) {
        return new Linq<>(asList(collection, true), false);
    }

    public static <T> Linq<T> from(T one) {
        return from(Arrays.toList(one));
    }

    @SafeVarargs
    public static <T> Linq<T> from(T... array) {
        return from(Arrays.toList(array));
    }

    public static <T> Linq<T> from(@NonNull Stream<T> stream) {
        return from(stream::iterator, stream.isParallel());
    }

    public static <T> Linq<T> from(Iterable<T> iterable) {
        return from(iterable, false);
    }

    public static <T> Linq<T> from(Iterable<T> iterable, boolean isParallel) {
        if (iterable == null) {
            iterable = Collections.emptyList();
        }
        return new Linq<>(iterable, isParallel);
    }
    //endregion

    //region Member
    private final Iterable<T> data;
    private final boolean parallel;

    public Stream<T> stream() {
        return StreamSupport.stream(data.spliterator(), parallel);
    }

    private <TR> List<TR> newList() {
        int count = count();
        return parallel ? newConcurrentList(count, false) : new ArrayList<>(count);
    }

    private <TR> Set<TR> newSet() {
        int count = count();
        return parallel ? Collections.synchronizedSet(new LinkedHashSet<>(count)) : new LinkedHashSet<>(count);
    }

    private <TK, TR> Map<TK, TR> newMap() {
        int count = count();
        return parallel ? Collections.synchronizedMap(new LinkedHashMap<>(count)) : new LinkedHashMap<>(count);
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
        return me(stream().map(p -> {
            try {
                return selector.invoke(p, counter.getAndIncrement());
            } catch (Throwable e) {
                throw InvalidException.sneaky(e);
            }
        }));
    }

    public <TR> Linq<TR> selectMany(BiFunc<T, Iterable<TR>> selector) {
        return me(stream().flatMap(p -> newStream(selector.apply(p))));
    }

    public <TR> Linq<TR> selectMany(BiFuncWithIndex<T, Iterable<TR>> selector) {
        AtomicInteger counter = new AtomicInteger();
        return me(stream().flatMap(p -> newStream(quietly(() -> selector.invoke(p, counter.getAndIncrement())))));
    }

    public Linq<T> where(PredicateFunc<T> predicate) {
        return me(stream().filter(predicate));
    }

    public Linq<T> where(PredicateFuncWithIndex<T> predicate) {
        AtomicInteger counter = new AtomicInteger();
        return me(stream().filter(p -> {
            try {
                return predicate.invoke(p, counter.getAndIncrement());
            } catch (Throwable e) {
                throw InvalidException.sneaky(e);
            }
        }));
    }

    public <TI, TR> Linq<TR> join(Iterable<TI> inner, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return me(stream().flatMap(p -> newStream(inner).filter(p2 -> keySelector.test(p, p2)).map(p3 -> resultSelector.apply(p, p3))));
    }

    public <TI, TR> Linq<TR> join(BiFunc<T, TI> innerSelector, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return join(stream().map(innerSelector).collect(Collectors.toList()), keySelector, resultSelector);
    }

    public <TI, TR> Linq<TR> joinMany(BiFunc<T, Iterable<TI>> innerSelector, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return join(stream().flatMap(p -> newStream(innerSelector.apply(p))).collect(Collectors.toList()), keySelector, resultSelector);
    }

    public <TI, TR> Linq<TR> leftJoin(Iterable<TI> inner, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return me(stream().flatMap(p -> {
            if (!newStream(inner).anyMatch(p2 -> keySelector.test(p, p2))) {
                return Stream.of(resultSelector.apply(p, null));
            }
            return newStream(inner).filter(p2 -> keySelector.test(p, p2)).map(p3 -> resultSelector.apply(p, p3));
        }));
    }

    public <TI, TR> Linq<TR> leftJoin(BiFunc<T, TI> innerSelector, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return leftJoin(stream().map(innerSelector).collect(Collectors.toList()), keySelector, resultSelector);
    }

    public <TI, TR> Linq<TR> leftJoinMany(BiFunc<T, Iterable<TI>> innerSelector, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
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
        return me(stream().sorted(getComparator(p -> ThreadLocalRandom.current().nextInt(0, 100))));
    }

    public <TK> Linq<T> orderBy(BiFunc<T, TK> keySelector) {
//        return me(stream().sorted(Comparator.nullsLast(Comparator.comparing((Function) keySelector))));
        return me(stream().sorted(getComparator(keySelector)));
    }

    @SuppressWarnings(NON_RAW_TYPES)
    public static <T, TK> Comparator<T> getComparator(BiFunc<T, TK> keySelector) {
        return (p1, p2) -> {
            Comparable c1 = as(keySelector.apply(p1), Comparable.class);
            Comparable c2 = as(keySelector.apply(p2), Comparable.class);
            if (c1 == null || c2 == null) {
                return c1 == null ? (c2 == null ? 0 : 1) : -1;
            }
            return c1.compareTo(c2);
        };
    }

    public <TK> Linq<T> orderByDescending(BiFunc<T, TK> keySelector) {
        return me(stream().sorted(getComparator(keySelector).reversed()));
    }

    public Linq<T> orderByMany(BiFunc<T, List<Object>> keySelector) {
        return me(stream().sorted(getComparatorMany(keySelector)));
    }

    @SuppressWarnings(NON_RAW_TYPES)
    public static <T> Comparator<T> getComparatorMany(BiFunc<T, List<Object>> keySelector) {
        return (p1, p2) -> {
            List<Object> k1s = keySelector.apply(p1), k2s = keySelector.apply(p2);
            for (int i = 0; i < k1s.size(); i++) {
                Comparable c1 = as(k1s.get(i), Comparable.class);
                Comparable c2 = as(k2s.get(i), Comparable.class);
                if (c1 == null || c2 == null) {
                    return c1 == null ? (c2 == null ? 0 : 1) : -1;
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

    public Linq<T> orderByDescendingMany(BiFunc<T, List<Object>> keySelector) {
        return me(stream().sorted(getComparatorMany(keySelector).reversed()));
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

    public <TK, TR> Linq<TR> groupBy(BiFunc<T, TK> keySelector, BiFunction<TK, Linq<T>, TR> resultSelector) {
        Map<TK, List<T>> map = stream().collect(Collectors.groupingBy(keySelector, this::newMap, Collectors.toList()));
        List<TR> result = newList();
        for (Map.Entry<TK, List<T>> entry : map.entrySet()) {
            result.add(resultSelector.apply(entry.getKey(), from(entry.getValue())));
        }
        return me(result);
    }

    public <TK, TR> Map<TK, TR> groupByIntoMap(BiFunc<T, TK> keySelector, BiFunction<TK, Linq<T>, TR> resultSelector) {
        Map<TK, List<T>> map = stream().collect(Collectors.groupingBy(keySelector, this::newMap, Collectors.toList()));
        Map<TK, TR> result = newMap();
        for (Map.Entry<TK, List<T>> entry : map.entrySet()) {
            result.put(entry.getKey(), resultSelector.apply(entry.getKey(), Linq.from(entry.getValue())));
        }
        return result;
    }

    public <TR> Linq<TR> groupByMany(BiFunc<T, List<Object>> keySelector, BiFunction<List<Object>, Linq<T>, TR> resultSelector) {
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
        if (data instanceof Collection) {
            return ((Collection<T>) data).size();
        }
        return (int) stream().count();
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
        return where(p -> Reflects.isInstance(p, type)).select(p -> (TR) p);
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

    //orElseGet use Extends.ifNull instead
    public T firstOrDefault(T defaultValue) {
        return stream().findFirst().orElse(defaultValue);
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
            if (!quietly(() -> predicate.invoke(p, i))) {
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
            if (!quietly(() -> predicate.invoke(p, i))) {
                flags |= EachFunc.BREAK;
                return flags;
            }
            flags |= EachFunc.ACCEPT;
            return flags;
        }, "takeWhile");
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
            type = t.getClass();
            break;
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
        List<T> result = newList();
        if (data instanceof Collection) {
            result.addAll((Collection<T>) data);
        } else {
            for (T item : data) {
                result.add(item);
            }
        }
        return result;
    }

    public Set<T> toSet() {
        Set<T> result = newSet();
        if (data instanceof Collection) {
            result.addAll((Collection<T>) data);
        } else {
            for (T item : data) {
                result.add(item);
            }
        }
        return result;
    }

    public <TK> Map<TK, T> toMap(BiFunc<T, TK> keySelector) {
        return toMap(keySelector, p -> p);
    }

    //Collectors.toMap 会校验value为null的情况
    @SneakyThrows
    public <TK, TR> Map<TK, TR> toMap(BiFunc<T, TK> keySelector, BiFunc<T, TR> resultSelector) {
        Map<TK, TR> result = newMap();
        stream().forEach(item -> result.put(keySelector.apply(item), resultSelector.apply(item)));
        return result;
    }
}
