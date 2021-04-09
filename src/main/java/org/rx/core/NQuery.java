package org.rx.core;

import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.rx.annotation.ErrorCode;
import org.rx.bean.$;
import org.rx.bean.Decimal;
import org.rx.core.exception.ApplicationException;
import org.rx.core.exception.InvalidException;
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

import static org.rx.core.App.*;

/**
 * https://msdn.microsoft.com/en-us/library/bb738550(v=vs.110).aspx
 *
 * @param <T>
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class NQuery<T> implements Iterable<T>, Serializable {
    //region staticMembers
    public static <T> List<T> asList(Object arrayOrIterable) {
        return asList(arrayOrIterable, true);
    }

    @SuppressWarnings(NON_WARNING)
    @ErrorCode("argError")
    public static <T> List<T> asList(Object arrayOrIterable, boolean throwOnEmpty) {
        require(arrayOrIterable);

        Class type = arrayOrIterable.getClass();
        if (type.isArray()) {
            int length = Array.getLength(arrayOrIterable);
            List<T> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                list.add((T) Array.get(arrayOrIterable, i));
            }
            return list;
        }

        Iterable<T> iterable;
        if ((iterable = as(arrayOrIterable, Iterable.class)) != null) {
            return toList(iterable);
        }

        if (throwOnEmpty) {
            throw new ApplicationException("argError", values(type.getSimpleName()));
        }
        return new ArrayList<>();
    }

    public static <T> List<T> toList(Iterator<T> iterator) {
        return IteratorUtils.toList(iterator);
    }

    public static <T> List<T> toList(Iterable<T> iterable) {
        return IterableUtils.toList(iterable);
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
    private final Iterable<T> data;
    private final boolean isParallel;

    public Stream<T> stream() {
        return StreamSupport.stream(data.spliterator(), isParallel);
    }

    @Override
    public Iterator<T> iterator() {
        return data.iterator();
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
            final AtomicBoolean breaker = new AtomicBoolean();
            final AtomicInteger counter = new AtomicInteger();

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

    @SneakyThrows
    public NQuery<T> each(PredicateFuncWithIndex<T> func) {
        Iterator<T> tor = this.iterator();
        int i = 0;
        while (tor.hasNext()) {
            if (!func.invoke(tor.next(), i++)) {
                break;
            }
        }
        return this;
    }

    public <TR> NQuery<TR> select(BiFunc<T, TR> selector) {
        return me(stream().map(selector.toFunction()));
    }

    public <TR> NQuery<TR> select(BiFuncWithIndex<T, TR> selector) {
        AtomicInteger counter = new AtomicInteger();
        return me(stream().map(p -> sneakyInvoke(() -> selector.invoke(p, counter.getAndIncrement()))));
    }

    public <TR> NQuery<TR> selectMany(BiFunc<T, Iterable<TR>> selector) {
        return me(stream().flatMap(p -> newStream(sneakyInvoke(() -> selector.invoke(p)))));
    }

    public <TR> NQuery<TR> selectMany(BiFuncWithIndex<T, Iterable<TR>> selector) {
        AtomicInteger counter = new AtomicInteger();
        return me(stream().flatMap(p -> newStream(sneakyInvoke(() -> selector.invoke(p, counter.getAndIncrement())))));
    }

    public NQuery<T> where(PredicateFunc<T> predicate) {
        return me(stream().filter(predicate.toPredicate()));
    }

    public NQuery<T> where(PredicateFuncWithIndex<T> predicate) {
        AtomicInteger counter = new AtomicInteger();
        return me(stream().filter(p -> sneakyInvoke(() -> predicate.invoke(p, counter.getAndIncrement()))));
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

    @SuppressWarnings(NON_WARNING)
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

    @SuppressWarnings(NON_WARNING)
    public NQuery<T> except(Iterable<T> set) {
        return me(stream().filter(p -> !newStream(set).anyMatch(p2 -> p2.equals(p))));
    }

    public NQuery<T> intersect(Iterable<T> set) {
        return me(stream().filter(p -> newStream(set).anyMatch(p2 -> p2.equals(p))));
    }

    public NQuery<T> union(Iterable<T> set) {
        return concat(set);
    }

    public <TK> NQuery<T> orderBy(BiFunc<T, TK> keySelector) {
        return me(stream().sorted(getComparator(keySelector)));
    }

    @SuppressWarnings(NON_WARNING)
    public static <T, TK> Comparator<T> getComparator(BiFunc<T, TK> keySelector) {
        return (p1, p2) -> sneakyInvoke(() -> {
            Comparable c1 = as(keySelector.invoke(p1), Comparable.class);
            Comparable c2 = as(keySelector.invoke(p2), Comparable.class);
            if (c1 == null || c2 == null) {
                return 0;
            }
            return c1.compareTo(c2);
        });
    }

    public <TK> NQuery<T> orderByDescending(BiFunc<T, TK> keySelector) {
        return me(stream().sorted(getComparator(keySelector).reversed()));
    }

    public NQuery<T> orderByMany(BiFunc<T, List<Object>> keySelector) {
        return me(stream().sorted(getComparatorMany(keySelector)));
    }

    @SuppressWarnings(NON_WARNING)
    public static <T> Comparator<T> getComparatorMany(BiFunc<T, List<Object>> keySelector) {
        return (p1, p2) -> sneakyInvoke(() -> {
            List<Object> k1s = keySelector.invoke(p1), k2s = keySelector.invoke(p2);
            for (int i = 0; i < k1s.size(); i++) {
                Comparable c1 = as(k1s.get(i), Comparable.class);
                Comparable c2 = as(k2s.get(i), Comparable.class);
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
        });
    }

    public NQuery<T> orderByDescendingMany(BiFunc<T, List<Object>> keySelector) {
        return me(stream().sorted(getComparatorMany(keySelector).reversed()));
    }

    @SuppressWarnings(NON_WARNING)
    public NQuery<T> reverse() {
        return me(stream().sorted((Comparator<T>) Comparator.reverseOrder()));
    }

    public <TK, TR> NQuery<TR> groupBy(BiFunc<T, TK> keySelector, BiFunction<TK, NQuery<T>, TR> resultSelector) {
        Map<TK, List<T>> map = stream().collect(Collectors.groupingBy(keySelector.toFunction(), this::newMap, Collectors.toList()));
        List<TR> result = newList();
        for (Map.Entry<TK, List<T>> entry : map.entrySet()) {
            result.add(resultSelector.apply(entry.getKey(), of(entry.getValue())));
        }
        return me(result);
    }

    public <TR> NQuery<TR> groupByMany(BiFunc<T, List<Object>> keySelector, BiFunction<List<Object>, NQuery<T>, TR> resultSelector) {
//        Map<String, Tuple<Object[], List<T>>> map = newMap();
//        stream().forEach(t -> {
//            Object[] ks = sneakyInvoke(() -> keySelector.invoke(t));
//            map.computeIfAbsent(toJsonString(ks), p -> Tuple.of(ks, newList())).right.add(t);
//        });
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
        return (int) stream().filter(predicate.toPredicate()).count();
    }

    public T max() {
        return max(stream());
    }

    @SuppressWarnings(NON_WARNING)
    private <TR> TR max(Stream<TR> stream) {
        return stream.max((Comparator<TR>) Comparator.naturalOrder()).orElse(null);
    }

    public <TR> TR max(BiFunc<T, TR> selector) {
        return max(stream().map(selector.toFunction()));
    }

    public T min() {
        return min(stream());
    }

    @SuppressWarnings(NON_WARNING)
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
        $<Decimal> sumValue = $.$(Decimal.ZERO);
        stream().forEach(p -> {
            try {
                sumValue.v = sumValue.v.add(selector.invoke(p));
            } catch (Throwable e) {
                throw InvalidException.sneaky(e);
            }
        });
        return sumValue.v;
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

    public T first(PredicateFunc<T> predicate) {
        return stream().filter(predicate.toPredicate()).findFirst().get();
    }

    public T firstOrDefault() {
        return firstOrDefault((T) null);
    }

    public T firstOrDefault(T defaultValue) {
        return stream().findFirst().orElse(defaultValue);
    }

    public T firstOrDefault(PredicateFunc<T> predicate) {
        return stream().filter(predicate.toPredicate()).findFirst().orElse(null);
    }

    @SuppressWarnings(NON_WARNING)
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
            int flags = EachFunc.None;
            if (doAccept.get()) {
                flags |= EachFunc.Accept;
                return flags;
            }
            if (!sneakyInvoke(() -> predicate.invoke(p, i))) {
                doAccept.set(true);
                flags |= EachFunc.Accept;
            }
            return flags;
        });
    }

    public NQuery<T> take(int count) {
        return me(stream().limit(count));
    }

    public NQuery<T> takeWhile(PredicateFunc<T> predicate) {
        return takeWhile((p, i) -> predicate.invoke(p));
    }

    public NQuery<T> takeWhile(PredicateFuncWithIndex<T> predicate) {
        return me((p, i) -> {
            int flags = EachFunc.None;
            if (!sneakyInvoke(() -> predicate.invoke(p, i))) {
                flags |= EachFunc.Break;
                return flags;
            }
            flags |= EachFunc.Accept;
            return flags;
        });
    }

    public String toJoinString(String delimiter, BiFunc<T, String> selector) {
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
