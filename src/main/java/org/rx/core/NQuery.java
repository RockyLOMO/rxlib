package org.rx.core;

import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
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
public final class NQuery<T> implements Iterable<T>, Serializable {
    private static final long serialVersionUID = -7167070585936243198L;

    //region staticMembers
    public static boolean couldBeCollection(Class<?> type) {
        return Iterable.class.isAssignableFrom(type)
                || type.isArray()
                || Iterator.class.isAssignableFrom(type);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @ErrorCode("argError")
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
            throw new ApplicationException("argError", values(type.getSimpleName()));
        }
        ArrayList<T> list = new ArrayList<>();
        list.add((T) collection);
        return list;
    }

    public static <T> NQuery<T> ofCollection(Object collection) {
        return new NQuery<>(asList(collection, true), false);
    }

    public static <T> NQuery<T> of(T one) {
        return of(Arrays.toList(one));
    }

    @SafeVarargs
    public static <T> NQuery<T> of(T... array) {
        return of(Arrays.toList(array));
    }

    public static <T> NQuery<T> of(@NonNull Stream<T> stream) {
        return of(stream::iterator, stream.isParallel());
    }

    public static <T> NQuery<T> of(Iterable<T> iterable) {
        return of(iterable, false);
    }

    public static <T> NQuery<T> of(Iterable<T> iterable, boolean isParallel) {
        if (iterable == null) {
            iterable = Collections.emptyList();
        }
        return new NQuery<>(iterable, isParallel);
    }
    //endregion

    //region Member
    private final Iterable<T> data;
    private final boolean isParallel;

    public Stream<T> stream() {
        return StreamSupport.stream(data.spliterator(), isParallel);
    }

    private <TR> List<TR> newList() {
        int count = count();
        return isParallel ? newConcurrentList(count, false) : new ArrayList<>(count);
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

    @SneakyThrows
    private NQuery<T> me(EachFunc<T> func, String prevMethod) {
        if (isParallel) {
            log.warn("Not supported parallel {}", prevMethod);
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
        }, isParallel);
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

    public <TR> NQuery<TR> select(BiFunc<T, TR> selector) {
        return me(stream().map(selector.toFunction()));
    }

    public <TR> NQuery<TR> select(BiFuncWithIndex<T, TR> selector) {
        AtomicInteger counter = new AtomicInteger();
        return me(stream().map(p -> {
            try {
                return selector.invoke(p, counter.getAndIncrement());
            } catch (Throwable e) {
                throw InvalidException.sneaky(e);
            }
        }));
    }

    public <TR> NQuery<TR> selectMany(BiFunc<T, Iterable<TR>> selector) {
        return me(stream().flatMap(p -> newStream(sneakyInvoke(() -> selector.invoke(p)))));
    }

    public <TR> NQuery<TR> selectMany(BiFuncWithIndex<T, Iterable<TR>> selector) {
        AtomicInteger counter = new AtomicInteger();
        return me(stream().flatMap(p -> newStream(sneakyInvoke(() -> selector.invoke(p, counter.getAndIncrement())))));
    }

    public NQuery<T> where(PredicateFunc<T> predicate) {
        return me(stream().filter(p -> {
            try {
                return predicate.invoke(p);
            } catch (Throwable e) {
                throw InvalidException.sneaky(e);
            }
        }));
    }

    public NQuery<T> where(PredicateFuncWithIndex<T> predicate) {
        AtomicInteger counter = new AtomicInteger();
        return me(stream().filter(p -> {
            try {
                return predicate.invoke(p, counter.getAndIncrement());
            } catch (Throwable e) {
                throw InvalidException.sneaky(e);
            }
        }));
    }

    public <TI, TR> NQuery<TR> join(Iterable<TI> inner, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return me(stream().flatMap(p -> newStream(inner).filter(p2 -> keySelector.test(p, p2)).map(p3 -> resultSelector.apply(p, p3))));
    }

    public <TI, TR> NQuery<TR> join(BiFunc<T, TI> innerSelector, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return join(stream().map(innerSelector.toFunction()).collect(Collectors.toList()), keySelector, resultSelector);
    }

    public <TI, TR> NQuery<TR> joinMany(BiFunc<T, Iterable<TI>> innerSelector, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return join(stream().flatMap(p -> newStream(sneakyInvoke(() -> innerSelector.invoke(p)))).collect(Collectors.toList()), keySelector, resultSelector);
    }

    public <TI, TR> NQuery<TR> leftJoin(Iterable<TI> inner, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return me(stream().flatMap(p -> {
            if (!newStream(inner).anyMatch(p2 -> keySelector.test(p, p2))) {
                return Stream.of(resultSelector.apply(p, null));
            }
            return newStream(inner).filter(p2 -> keySelector.test(p, p2)).map(p3 -> resultSelector.apply(p, p3));
        }));
    }

    public <TI, TR> NQuery<TR> leftJoin(BiFunc<T, TI> innerSelector, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return leftJoin(stream().map(innerSelector.toFunction()).collect(Collectors.toList()), keySelector, resultSelector);
    }

    public <TI, TR> NQuery<TR> leftJoinMany(BiFunc<T, Iterable<TI>> innerSelector, BiPredicate<T, TI> keySelector, BiFunction<T, TI, TR> resultSelector) {
        return leftJoin(stream().flatMap(p -> newStream(sneakyInvoke(() -> innerSelector.invoke(p)))).collect(Collectors.toList()), keySelector, resultSelector);
    }

    public boolean all(PredicateFunc<T> predicate) {
        return stream().allMatch(predicate.toPredicate());
    }

    public boolean any() {
        return stream().findAny().isPresent();
    }

    public boolean any(PredicateFunc<T> predicate) {
        return stream().anyMatch(predicate.toPredicate());
    }

    public boolean contains(T item) {
        return stream().anyMatch(p -> p.equals(item));
    }

    public NQuery<T> concat(Iterable<T> set) {
        return me(Stream.concat(stream(), newStream(set)));
    }

    public NQuery<T> distinct() {
        return me(stream().distinct());
    }

    public NQuery<T> except(Iterable<T> set) {
        return me(stream().filter(p -> !newStream(set).anyMatch(p2 -> p2.equals(p))));
    }

    public NQuery<T> intersection(Iterable<T> set) {
        return me(stream().filter(p -> newStream(set).anyMatch(p2 -> p2.equals(p))));
    }

    public NQuery<T> difference(Iterable<T> set) {
        return NQuery.of(CollectionUtils.disjunction(this, set));
    }

    public NQuery<T> union(Iterable<T> set) {
        return NQuery.of(CollectionUtils.union(this, set));
    }

    public <TK> NQuery<T> orderBy(BiFunc<T, TK> keySelector) {
//        return me(stream().sorted(Comparator.nullsLast(Comparator.comparing((Function) keySelector.toFunction()))));
        return me(stream().sorted(getComparator(keySelector)));
    }

    @SuppressWarnings(NON_RAW_TYPES)
    public static <T, TK> Comparator<T> getComparator(BiFunc<T, TK> keySelector) {
        return (p1, p2) -> {
            try {
                Comparable c1 = as(keySelector.invoke(p1), Comparable.class);
                Comparable c2 = as(keySelector.invoke(p2), Comparable.class);
                if (c1 == null || c2 == null) {
                    return c1 == null ? (c2 == null ? 0 : 1) : -1;
                }
                return c1.compareTo(c2);
            } catch (Throwable e) {
                throw InvalidException.sneaky(e);
            }
        };
    }

    public <TK> NQuery<T> orderByDescending(BiFunc<T, TK> keySelector) {
        return me(stream().sorted(getComparator(keySelector).reversed()));
    }

    public NQuery<T> orderByMany(BiFunc<T, List<Object>> keySelector) {
        return me(stream().sorted(getComparatorMany(keySelector)));
    }

    @SuppressWarnings(NON_RAW_TYPES)
    public static <T> Comparator<T> getComparatorMany(BiFunc<T, List<Object>> keySelector) {
        return (p1, p2) -> {
            try {
                List<Object> k1s = keySelector.invoke(p1), k2s = keySelector.invoke(p2);
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
            } catch (Throwable e) {
                throw InvalidException.sneaky(e);
            }
        };
    }

    public NQuery<T> orderByDescendingMany(BiFunc<T, List<Object>> keySelector) {
        return me(stream().sorted(getComparatorMany(keySelector).reversed()));
    }

    @SuppressWarnings(NON_UNCHECKED)
    public NQuery<T> reverse() {
        try {
            return me(stream().sorted((Comparator<T>) Comparator.reverseOrder()));
        } catch (Exception e) {
            log.warn("reverse fail, {}", e.getMessage());
            List<T> list = toList();
            Collections.reverse(list);
            return me(list);
        }
    }

    public <TK, TR> NQuery<TR> groupBy(BiFunc<T, TK> keySelector, BiFunction<TK, NQuery<T>, TR> resultSelector) {
        Map<TK, List<T>> map = stream().collect(Collectors.groupingBy(keySelector.toFunction(), this::newMap, Collectors.toList()));
        List<TR> result = newList();
        for (Map.Entry<TK, List<T>> entry : map.entrySet()) {
            result.add(resultSelector.apply(entry.getKey(), of(entry.getValue())));
        }
        return me(result);
    }

    public <TK, TR> Map<TK, TR> groupByIntoMap(BiFunc<T, TK> keySelector, BiFunction<TK, NQuery<T>, TR> resultSelector) {
        Map<TK, List<T>> map = stream().collect(Collectors.groupingBy(keySelector.toFunction(), this::newMap, Collectors.toList()));
        Map<TK, TR> result = newMap();
        for (Map.Entry<TK, List<T>> entry : map.entrySet()) {
            result.put(entry.getKey(), resultSelector.apply(entry.getKey(), NQuery.of(entry.getValue())));
        }
        return result;
    }

    public <TR> NQuery<TR> groupByMany(BiFunc<T, List<Object>> keySelector, BiFunction<List<Object>, NQuery<T>, TR> resultSelector) {
        Map<List<Object>, List<T>> map = stream().collect(Collectors.groupingBy(keySelector.toFunction(), this::newMap, Collectors.toList()));
        List<TR> result = newList();
        for (Map.Entry<List<Object>, List<T>> entry : map.entrySet()) {
            result.add(resultSelector.apply(entry.getKey(), of(entry.getValue())));
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
        return (int) stream().filter(p -> {
            try {
                return predicate.invoke(p);
            } catch (Throwable e) {
                throw InvalidException.sneaky(e);
            }
        }).count();
    }

    public T max() {
        return max(stream());
    }

    @SuppressWarnings(NON_UNCHECKED)
    private <TR> TR max(Stream<TR> stream) {
        return stream.max((Comparator<TR>) Comparator.naturalOrder()).orElse(null);
    }

    public <TR> TR max(BiFunc<T, TR> selector) {
        return max(stream().map(selector.toFunction()));
    }

    public T min() {
        return min(stream());
    }

    @SuppressWarnings(NON_UNCHECKED)
    private <TR> TR min(Stream<TR> stream) {
        return stream.min((Comparator<TR>) Comparator.naturalOrder()).orElse(null);
    }

    public <TR> TR min(BiFunc<T, TR> selector) {
        return min(stream().map(selector.toFunction()));
    }

    public double sum(ToDoubleFunction<T> selector) {
        return stream().mapToDouble(selector).sum();
    }

    public Decimal sumDecimal(BiFunc<T, Decimal> selector) {
        $<Decimal> sumValue = $(Decimal.ZERO);
        stream().forEach(p -> {
            try {
                sumValue.v = sumValue.v.add(selector.invoke(p));
            } catch (Throwable e) {
                throw InvalidException.sneaky(e);
            }
        });
        return sumValue.v;
    }

    @SuppressWarnings(NON_UNCHECKED)
    public <TR> NQuery<TR> cast() {
        return (NQuery<TR>) this;
    }

    @SuppressWarnings(NON_UNCHECKED)
    public <TR> NQuery<TR> ofType(Class<TR> type) {
        return where(p -> Reflects.isInstance(p, type)).select(p -> (TR) p);
    }

    public T first() {
        return stream().findFirst().get();
    }

    public T first(PredicateFunc<T> predicate) {
        return stream().filter(predicate.toPredicate()).findFirst().get();
    }

    public T firstOrDefault() {
        return firstOrDefault((T) null);
    }

    public T firstOrDefault(T defaultValue) {
        return stream().findFirst().orElse(defaultValue);
    }

    public T firstOrDefault(Supplier<T> defaultValue) {
        return stream().findFirst().orElseGet(defaultValue);
    }

    public T firstOrDefault(PredicateFunc<T> predicate) {
        return stream().filter(predicate.toPredicate()).findFirst().orElse(null);
    }

    public T last() {
        return Streams.findLast(stream()).get();
    }

    public T last(PredicateFunc<T> predicate) {
        return Streams.findLast(stream().filter(predicate.toPredicate())).get();
    }

    public T lastOrDefault() {
        return lastOrDefault((T) null);
    }

    public T lastOrDefault(T defaultValue) {
        return Streams.findLast(stream()).orElse(defaultValue);
    }

    public T lastOrDefault(Supplier<T> defaultValue) {
        return Streams.findLast(stream()).orElseGet(defaultValue);
    }

    public T lastOrDefault(PredicateFunc<T> predicate) {
        return Streams.findLast(stream().filter(predicate.toPredicate())).orElse(null);
    }

    public T single() {
        return single(null);
    }

    @ErrorCode
    public T single(PredicateFunc<T> predicate) {
        Stream<T> stream = stream();
        if (predicate != null) {
            stream = stream.filter(predicate.toPredicate());
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
            stream = stream.filter(predicate.toPredicate());
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

    public NQuery<T> skipWhile(PredicateFunc<T> predicate) {
        return skipWhile((p, i) -> predicate.invoke(p));
    }

    public NQuery<T> skipWhile(PredicateFuncWithIndex<T> predicate) {
        AtomicBoolean doAccept = new AtomicBoolean();
        return me((p, i) -> {
            int flags = EachFunc.NONE;
            if (doAccept.get()) {
                flags |= EachFunc.ACCEPT;
                return flags;
            }
            if (!sneakyInvoke(() -> predicate.invoke(p, i))) {
                doAccept.set(true);
                flags |= EachFunc.ACCEPT;
            }
            return flags;
        }, "skipWhile");
    }

    public NQuery<T> take(int count) {
        return me(stream().limit(count));
    }

    public NQuery<T> takeWhile(PredicateFunc<T> predicate) {
        return takeWhile((p, i) -> predicate.invoke(p));
    }

    public NQuery<T> takeWhile(PredicateFuncWithIndex<T> predicate) {
        return me((p, i) -> {
            int flags = EachFunc.NONE;
            if (!sneakyInvoke(() -> predicate.invoke(p, i))) {
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
        Iterables.addAll(result, data);
        return result;
    }

    public Set<T> toSet() {
        Set<T> result = newSet();
        Iterables.addAll(result, data);
        return result;
    }

    public <TK> Map<TK, T> toMap(BiFunc<T, TK> keySelector) {
        return toMap(keySelector, p -> p);
    }

    //Collectors.toMap 会校验value为null的情况
    @SneakyThrows
    public <TK, TR> Map<TK, TR> toMap(BiFunc<T, TK> keySelector, BiFunc<T, TR> resultSelector) {
        Map<TK, TR> result = newMap();
        stream().forEach(item -> {
            try {
                result.put(keySelector.invoke(item), resultSelector.invoke(item));
            } catch (Throwable e) {
                throw InvalidException.sneaky(e);
            }
        });
        return result;
    }
}
