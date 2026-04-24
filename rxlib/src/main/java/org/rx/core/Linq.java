package org.rx.core;

import io.netty.util.internal.ThreadLocalRandom;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.IteratorUtils;
import org.rx.annotation.ErrorCode;
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

import static org.rx.core.Constants.NON_RAW_TYPES;
import static org.rx.core.Constants.NON_UNCHECKED;
import static org.rx.core.Extends.*;

/**
 * https://msdn.microsoft.com/en-us/library/bb738550(v=vs.110).aspx
 * Eager query wrapper for reusable enumeration, not a lazy Stream replacement.
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
            return IteratorUtils.toList((Iterator<T>) collection);
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
        boolean isParallel = stream.isParallel();
        try {
            return from(stream.collect(Collectors.toList()), isParallel);
        } finally {
            stream.close();
        }
    }

    public static <T> Linq<T> from(Iterable<T> iterable) {
        return from(iterable, false);
    }

    @SuppressWarnings(NON_UNCHECKED)
    public static <T> Linq<T> from(Iterable<T> iterable, boolean isParallel) {
        if (iterable == null) {
            return EMPTY;
        }
        if (iterable instanceof Linq) {
            Linq<T> linq = (Linq<T>) iterable;
            return linq.parallel == isParallel ? linq : new Linq<>(linq.data, isParallel);
        }
        if (iterable instanceof Collection) {
            return new Linq<>(iterable, isParallel);
        }
        try {
            return new Linq<>(snapshot(iterable), isParallel);
        } finally {
            if (iterable instanceof AutoCloseable) {
                quietly(((AutoCloseable) iterable)::close);
            }
        }
    }
    //endregion

    //region Member
    private final Iterable<T> data;
    private final boolean parallel;

    private static int capacity(int size) {
        return size <= 0 ? 16 : Math.max((int) (size / 0.75F) + 1, 16);
    }

    public Stream<T> stream() {
        return StreamSupport.stream(data.spliterator(), parallel);
    }

    private <TR> List<TR> newList() {
        Collection<T> ts = asCollection();
        int count = ts != null ? ts.size() : 0;
        return newList(count);
    }

    private <TR> List<TR> newList(int count) {
        return parallel ? newConcurrentList(count, false) : new ArrayList<>(Math.max(count, 0));
    }

    private <TR> Set<TR> newSet() {
        Collection<T> ts = asCollection();
        int count = ts != null ? ts.size() : 16;
        return newSet(count);
    }

    private <TR> Set<TR> newSet(int count) {
        int initCapacity = capacity(count);
        return parallel ? Collections.synchronizedSet(new LinkedHashSet<>(initCapacity)) : new LinkedHashSet<>(initCapacity);
    }

    private <TK, TR> Map<TK, TR> newMap() {
        Collection<T> ts = asCollection();
        int count = ts != null ? ts.size() : 16;
        return newMap(count);
    }

    private <TK, TR> Map<TK, TR> newMap(int count) {
        int initCapacity = capacity(count);
        return parallel ? Collections.synchronizedMap(new LinkedHashMap<>(initCapacity)) : new LinkedHashMap<>(initCapacity);
    }

    private Collection<T> asCollection() {
        return data instanceof Collection ? (Collection<T>) data : null;
    }

    private int sizeHint() {
        Collection<T> ts = asCollection();
        return ts != null ? ts.size() : 0;
    }

    private static <TR> Stream<TR> streamOf(Iterable<TR> iterable) {
        return StreamSupport.stream(iterable.spliterator(), false);
    }

    private <TR> Linq<TR> me(Iterable<TR> set) {
        return from(set, parallel);
    }

    private <TR> Linq<TR> me(Stream<TR> stream) {
        return me(stream.collect(Collectors.toList()));
    }

    @SuppressWarnings(NON_UNCHECKED)
    private static <TR> List<TR> snapshot(Iterable<TR> iterable) {
        if (iterable == null) {
            return Collections.emptyList();
        }
        if (iterable instanceof List) {
            return (List<TR>) iterable;
        }

        Collection<TR> collection = iterable instanceof Collection ? (Collection<TR>) iterable : null;
        List<TR> result = collection != null ? new ArrayList<>(collection.size()) : new ArrayList<>();
        for (TR item : iterable) {
            result.add(item);
        }
        return result;
    }

    @SuppressWarnings(NON_RAW_TYPES)
    private static int compareComparable(Object left, Object right, boolean descending) {
        Comparable a = as(left, Comparable.class);
        Comparable b = as(right, Comparable.class);
        boolean nullFirst = false;
        if (a == null) {
            return b == null ? 0 : (nullFirst ? -1 : 1);
        }
        if (b == null) {
            return nullFirst ? 1 : -1;
        }
        return descending ? b.compareTo(a) : a.compareTo(b);
    }

    private static int compareComparableMany(List<Object> left, List<Object> right, boolean descending) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return right == null ? 0 : 1;
        }
        if (right == null) {
            return -1;
        }

        int size = Math.min(left.size(), right.size());
        for (int i = 0; i < size; i++) {
            int r = compareComparable(left.get(i), right.get(i), descending);
            if (r != 0) {
                return r;
            }
        }
        if (left.size() == right.size()) {
            return 0;
        }
        return descending ? Integer.compare(right.size(), left.size()) : Integer.compare(left.size(), right.size());
    }

    private static final class SortEntry<T, TK> {
        private final T value;
        private final TK key;

        private SortEntry(T value, TK key) {
            this.value = value;
            this.key = key;
        }
    }

    private static final class ManyKey {
        private final List<Object> values;
        private final int hash;

        private ManyKey(List<Object> values) {
            this.values = values;
            this.hash = values != null ? values.hashCode() : 0;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ManyKey)) {
                return false;
            }
            ManyKey that = (ManyKey) obj;
            return Objects.equals(values, that.values);
        }
    }

    private static <TK> int getCount(Map<TK, Integer> map, TK key) {
        Integer count = map.get(key);
        return count == null ? 0 : count;
    }

    private static <TK> void incrementCount(Map<TK, Integer> map, TK key) {
        map.put(key, getCount(map, key) + 1);
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
        if (parallel) {
            stream().forEach(action);
            return;
        }
        for (T item : data) {
            action.accept(item);
        }
    }

    public void forEachOrdered(Consumer<? super T> action) {
        if (parallel) {
            stream().forEachOrdered(action);
            return;
        }
        for (T item : data) {
            action.accept(item);
        }
    }

    public <TR> Linq<TR> select(BiFunc<T, TR> selector) {
        if (parallel) {
            return me(stream().map(selector));
        }
        List<TR> result = newList();
        for (T item : data) {
            result.add(selector.apply(item));
        }
        return me(result);
    }

    public <TR> Linq<TR> select(BiFuncWithIndex<T, TR> selector) {
        if (parallel) {
            AtomicInteger counter = new AtomicInteger();
            return me(stream().map(p -> selector.apply(p, counter.getAndIncrement())));
        }
        List<TR> result = newList();
        int index = 0;
        for (T item : data) {
            result.add(selector.apply(item, index++));
        }
        return me(result);
    }

    public <TR> Linq<TR> selectMany(BiFunc<T, Iterable<TR>> selector) {
        if (parallel) {
            return me(stream().flatMap(p -> streamOf(selector.apply(p))));
        }
        List<TR> result = newList();
        for (T item : data) {
            for (TR inner : selector.apply(item)) {
                result.add(inner);
            }
        }
        return me(result);
    }

    public <TR> Linq<TR> selectMany(BiFuncWithIndex<T, Iterable<TR>> selector) {
        if (parallel) {
            AtomicInteger counter = new AtomicInteger();
            return me(stream().flatMap(p -> streamOf(selector.apply(p, counter.getAndIncrement()))));
        }
        List<TR> result = newList();
        int index = 0;
        for (T item : data) {
            for (TR inner : selector.apply(item, index++)) {
                result.add(inner);
            }
        }
        return me(result);
    }

    public Linq<T> where(PredicateFunc<T> predicate) {
        if (parallel) {
            return me(stream().filter(predicate));
        }
        List<T> result = newList();
        for (T item : data) {
            if (predicate.test(item)) {
                result.add(item);
            }
        }
        return me(result);
    }

    public Linq<T> where(PredicateFuncWithIndex<T> predicate) {
        if (parallel) {
            AtomicInteger counter = new AtomicInteger();
            return me(stream().filter(p -> predicate.test(p, counter.getAndIncrement())));
        }
        List<T> result = newList();
        int index = 0;
        for (T item : data) {
            if (predicate.test(item, index++)) {
                result.add(item);
            }
        }
        return me(result);
    }

    public <TI, TR> Linq<TR> join(Iterable<TI> inner, BiPredicate<T, TI> keySelector, TripleFunc<T, TI, TR> resultSelector) {
        List<TI> right = snapshot(inner);
        if (parallel) {
            return me(stream().flatMap(p -> right.stream().filter(p2 -> keySelector.test(p, p2)).map(p3 -> resultSelector.apply(p, p3))));
        }
        List<TR> result = newList();
        for (T item : data) {
            for (TI rightItem : right) {
                if (keySelector.test(item, rightItem)) {
                    result.add(resultSelector.apply(item, rightItem));
                }
            }
        }
        return me(result);
    }

    public <TI, TR> Linq<TR> join(BiFunc<T, TI> innerSelector, BiPredicate<T, TI> keySelector, TripleFunc<T, TI, TR> resultSelector) {
        List<TI> inner = new ArrayList<>(capacity(sizeHint()));
        for (T item : data) {
            inner.add(innerSelector.apply(item));
        }
        return join(inner, keySelector, resultSelector);
    }

    public <TI, TR> Linq<TR> joinMany(BiFunc<T, Iterable<TI>> innerSelector, BiPredicate<T, TI> keySelector, TripleFunc<T, TI, TR> resultSelector) {
        List<TI> inner = new ArrayList<>();
        for (T item : data) {
            for (TI innerItem : innerSelector.apply(item)) {
                inner.add(innerItem);
            }
        }
        return join(inner, keySelector, resultSelector);
    }

    public <TI, TR> Linq<TR> leftJoin(Iterable<TI> right, BiPredicate<T, TI> keySelector, TripleFunc<T, TI, TR> resultSelector) {
        List<TI> rightItems = snapshot(right);
        if (parallel) {
            return me(stream().flatMap(p -> {
                List<TR> items = new ArrayList<>();
                for (TI rightItem : rightItems) {
                    if (keySelector.test(p, rightItem)) {
                        items.add(resultSelector.apply(p, rightItem));
                    }
                }
                if (items.isEmpty()) {
                    items.add(resultSelector.apply(p, null));
                }
                return items.stream();
            }));
        }
        List<TR> result = newList();
        for (T item : data) {
            boolean matched = false;
            for (TI rightItem : rightItems) {
                if (keySelector.test(item, rightItem)) {
                    matched = true;
                    result.add(resultSelector.apply(item, rightItem));
                }
            }
            if (!matched) {
                result.add(resultSelector.apply(item, null));
            }
        }
        return me(result);
    }

    public <TI, TR> Linq<TR> leftJoin(BiFunc<T, TI> innerSelector, BiPredicate<T, TI> keySelector, TripleFunc<T, TI, TR> resultSelector) {
        List<TI> inner = new ArrayList<>(capacity(sizeHint()));
        for (T item : data) {
            inner.add(innerSelector.apply(item));
        }
        return leftJoin(inner, keySelector, resultSelector);
    }

    public <TI, TR> Linq<TR> leftJoinMany(BiFunc<T, Iterable<TI>> innerSelector, BiPredicate<T, TI> keySelector, TripleFunc<T, TI, TR> resultSelector) {
        List<TI> inner = new ArrayList<>();
        for (T item : data) {
            for (TI innerItem : innerSelector.apply(item)) {
                inner.add(innerItem);
            }
        }
        return leftJoin(inner, keySelector, resultSelector);
    }

    public boolean all(PredicateFunc<T> predicate) {
        if (parallel) {
            return stream().allMatch(predicate);
        }
        for (T item : data) {
            if (!predicate.test(item)) {
                return false;
            }
        }
        return true;
    }

    public boolean any() {
        return iterator().hasNext();
    }

    public boolean any(PredicateFunc<T> predicate) {
        if (parallel) {
            return stream().anyMatch(predicate);
        }
        for (T item : data) {
            if (predicate.test(item)) {
                return true;
            }
        }
        return false;
    }

    public boolean contains(T item) {
        for (T p : data) {
            if (Objects.equals(p, item)) {
                return true;
            }
        }
        return false;
    }

    public Linq<T> concat(Iterable<T> set) {
        if (!iterator().hasNext()) {
            return me(snapshot(set));
        }
        List<T> result = newList();
        for (T item : data) {
            result.add(item);
        }
        for (T item : set) {
            result.add(item);
        }
        return me(result);
    }

    public Linq<T> distinct() {
        if (!parallel && data instanceof Set) {
            return this;
        }
        Set<T> result = newSet();
        for (T item : data) {
            result.add(item);
        }
        return me(result);
    }

    public Linq<T> except(Iterable<T> set) {
        Set<T> excluded = new HashSet<>(snapshot(set));
        List<T> result = newList();
        for (T item : data) {
            if (!excluded.contains(item)) {
                result.add(item);
            }
        }
        return me(result);
    }

    public Linq<T> intersection(Iterable<T> set) {
        Set<T> included = new HashSet<>(snapshot(set));
        List<T> result = newList();
        for (T item : data) {
            if (included.contains(item)) {
                result.add(item);
            }
        }
        return me(result);
    }

    public Linq<T> difference(Iterable<T> set) {
        List<T> right = snapshot(set);
        Map<T, Integer> rightCounts = new LinkedHashMap<>(capacity(right.size()));
        for (T item : right) {
            incrementCount(rightCounts, item);
        }

        List<T> result = newList();
        for (T item : data) {
            int count = getCount(rightCounts, item);
            if (count == 0) {
                result.add(item);
                continue;
            }
            rightCounts.put(item, count - 1);
        }
        for (T item : right) {
            int count = getCount(rightCounts, item);
            if (count == 0) {
                continue;
            }
            result.add(item);
            rightCounts.put(item, count - 1);
        }
        return me(result);
    }

    public Linq<T> union(Iterable<T> set) {
        List<T> right = snapshot(set);
        Map<T, Integer> rightCounts = new LinkedHashMap<>(capacity(right.size()));
        for (T item : right) {
            incrementCount(rightCounts, item);
        }

        List<T> result = newList();
        for (T item : data) {
            result.add(item);
            int count = getCount(rightCounts, item);
            if (count > 0) {
                rightCounts.put(item, count - 1);
            }
        }
        for (T item : right) {
            int count = getCount(rightCounts, item);
            if (count == 0) {
                continue;
            }
            result.add(item);
            rightCounts.put(item, count - 1);
        }
        return me(result);
    }

    //ListUtils.partition()
    public Linq<List<T>> partition(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size");
        }
        List<T> a = toList();
        List<List<T>> n = newList((a.size() + size - 1) / size);
        int f = 0, t = 0;
        while (f < a.size()) {
            t = Math.min(t + size, a.size());
            n.add(new ArrayList<>(a.subList(f, t)));
            f = t;
        }
        return me(n);
    }

    public Linq<T> orderByRand() {
        List<T> result = new ArrayList<>(snapshot(data));
        Collections.shuffle(result, ThreadLocalRandom.current());
        return me(result);
    }

    public <TK> Linq<T> orderBy(BiFunc<T, TK> keySelector) {
        List<SortEntry<T, TK>> entries = new ArrayList<>(capacity(sizeHint()));
        for (T item : data) {
            entries.add(new SortEntry<>(item, keySelector.apply(item)));
        }
        entries.sort((o1, o2) -> compareComparable(o1.key, o2.key, false));
        List<T> result = newList(entries.size());
        for (SortEntry<T, TK> entry : entries) {
            result.add(entry.value);
        }
        return me(result);
    }

    @SuppressWarnings(NON_RAW_TYPES)
    public static <T, TK> Comparator<T> getComparator(BiFunc<T, TK> keySelector, boolean descending) {
        return (p1, p2) -> compareComparable(keySelector.apply(p1), keySelector.apply(p2), descending);
    }

    public <TK> Linq<T> orderByDescending(BiFunc<T, TK> keySelector) {
        List<SortEntry<T, TK>> entries = new ArrayList<>(capacity(sizeHint()));
        for (T item : data) {
            entries.add(new SortEntry<>(item, keySelector.apply(item)));
        }
        entries.sort((o1, o2) -> compareComparable(o1.key, o2.key, true));
        List<T> result = newList(entries.size());
        for (SortEntry<T, TK> entry : entries) {
            result.add(entry.value);
        }
        return me(result);
    }

    public Linq<T> orderByMany(BiFunc<T, List<Object>> keySelector) {
        List<SortEntry<T, List<Object>>> entries = new ArrayList<>(capacity(sizeHint()));
        for (T item : data) {
            entries.add(new SortEntry<>(item, keySelector.apply(item)));
        }
        entries.sort((o1, o2) -> compareComparableMany(o1.key, o2.key, false));
        List<T> result = newList(entries.size());
        for (SortEntry<T, List<Object>> entry : entries) {
            result.add(entry.value);
        }
        return me(result);
    }

    @SuppressWarnings(NON_RAW_TYPES)
    public static <T> Comparator<T> getComparatorMany(BiFunc<T, List<Object>> keySelector, boolean descending) {
        return (p1, p2) -> compareComparableMany(keySelector.apply(p1), keySelector.apply(p2), descending);
    }

    public Linq<T> orderByDescendingMany(BiFunc<T, List<Object>> keySelector) {
        List<SortEntry<T, List<Object>>> entries = new ArrayList<>(capacity(sizeHint()));
        for (T item : data) {
            entries.add(new SortEntry<>(item, keySelector.apply(item)));
        }
        entries.sort((o1, o2) -> compareComparableMany(o1.key, o2.key, true));
        List<T> result = newList(entries.size());
        for (SortEntry<T, List<Object>> entry : entries) {
            result.add(entry.value);
        }
        return me(result);
    }

    public Linq<T> reverse() {
        List<T> list = newList(sizeHint());
        for (T item : data) {
            list.add(item);
        }
        Collections.reverse(list);
        return me(list);
    }

    public <TK, TR> Linq<TR> groupBy(BiFunc<T, TK> keySelector, TripleFunc<TK, Linq<T>, TR> resultSelector) {
        Map<TK, List<T>> map = newMap();
        for (T item : data) {
            TK key = keySelector.apply(item);
            List<T> group = map.get(key);
            if (group == null) {
                group = parallel ? newConcurrentList(false) : new ArrayList<>();
                map.put(key, group);
            }
            group.add(item);
        }
        List<TR> result = newList(map.size());
        for (Map.Entry<TK, List<T>> entry : map.entrySet()) {
            result.add(resultSelector.apply(entry.getKey(), from(entry.getValue())));
        }
        return me(result);
    }

    public <TK, TR> Map<TK, TR> groupByIntoMap(BiFunc<T, TK> keySelector, TripleFunc<TK, Linq<T>, TR> resultSelector) {
        Map<TK, List<T>> map = newMap();
        for (T item : data) {
            TK key = keySelector.apply(item);
            List<T> group = map.get(key);
            if (group == null) {
                group = parallel ? newConcurrentList(false) : new ArrayList<>();
                map.put(key, group);
            }
            group.add(item);
        }
        Map<TK, TR> result = newMap(map.size());
        for (Map.Entry<TK, List<T>> entry : map.entrySet()) {
            result.put(entry.getKey(), resultSelector.apply(entry.getKey(), Linq.from(entry.getValue())));
        }
        return result;
    }

    public <TR> Linq<TR> groupByMany(BiFunc<T, List<Object>> keySelector, TripleFunc<List<Object>, Linq<T>, TR> resultSelector) {
        Map<ManyKey, List<T>> map = newMap();
        for (T item : data) {
            ManyKey key = new ManyKey(keySelector.apply(item));
            List<T> group = map.get(key);
            if (group == null) {
                group = parallel ? newConcurrentList(false) : new ArrayList<>();
                map.put(key, group);
            }
            group.add(item);
        }
        List<TR> result = newList(map.size());
        for (Map.Entry<ManyKey, List<T>> entry : map.entrySet()) {
            result.add(resultSelector.apply(entry.getKey().values, from(entry.getValue())));
        }
        return me(result);
    }

    public Double average(ToDoubleFunction<T> selector) {
        if (parallel) {
            OptionalDouble q = stream().mapToDouble(selector).average();
            return q.isPresent() ? q.getAsDouble() : null;
        }
        double sum = 0D;
        int count = 0;
        for (T item : data) {
            sum += selector.applyAsDouble(item);
            count++;
        }
        return count == 0 ? null : sum / count;
    }

    public int count() {
        Collection<T> ts = asCollection();
        if (ts != null) {
            return ts.size();
        }
        if (parallel) {
            return (int) stream().count();
        }
        int count = 0;
        for (T item : data) {
            count++;
        }
        return count;
    }

    public int count(PredicateFunc<T> predicate) {
        if (parallel) {
            return (int) stream().filter(predicate).count();
        }
        int count = 0;
        for (T item : data) {
            if (predicate.test(item)) {
                count++;
            }
        }
        return count;
    }

    public T max() {
        if (parallel) {
            return max(stream());
        }
        return max(data);
    }

    @SuppressWarnings(NON_UNCHECKED)
    private <TR> TR max(Stream<TR> stream) {
        return stream.max((Comparator<TR>) Comparator.naturalOrder()).orElse(null);
    }

    @SuppressWarnings(NON_UNCHECKED)
    private static <TR> TR max(Iterable<TR> iterable) {
        Iterator<TR> iterator = iterable.iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        TR value = iterator.next();
        while (iterator.hasNext()) {
            TR candidate = iterator.next();
            if (((Comparable<TR>) candidate).compareTo(value) > 0) {
                value = candidate;
            }
        }
        return value;
    }

    public <TR> TR max(BiFunc<T, TR> selector) {
        if (parallel) {
            return max(stream().map(selector));
        }
        boolean found = false;
        TR value = null;
        for (T item : data) {
            TR candidate = selector.apply(item);
            if (!found) {
                value = candidate;
                found = true;
                continue;
            }
            if (((Comparable<TR>) candidate).compareTo(value) > 0) {
                value = candidate;
            }
        }
        return found ? value : null;
    }

    public T min() {
        if (parallel) {
            return min(stream());
        }
        return min(data);
    }

    @SuppressWarnings(NON_UNCHECKED)
    private <TR> TR min(Stream<TR> stream) {
        return stream.min((Comparator<TR>) Comparator.naturalOrder()).orElse(null);
    }

    @SuppressWarnings(NON_UNCHECKED)
    private static <TR> TR min(Iterable<TR> iterable) {
        Iterator<TR> iterator = iterable.iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        TR value = iterator.next();
        while (iterator.hasNext()) {
            TR candidate = iterator.next();
            if (((Comparable<TR>) candidate).compareTo(value) < 0) {
                value = candidate;
            }
        }
        return value;
    }

    public <TR> TR min(BiFunc<T, TR> selector) {
        if (parallel) {
            return min(stream().map(selector));
        }
        boolean found = false;
        TR value = null;
        for (T item : data) {
            TR candidate = selector.apply(item);
            if (!found) {
                value = candidate;
                found = true;
                continue;
            }
            if (((Comparable<TR>) candidate).compareTo(value) < 0) {
                value = candidate;
            }
        }
        return found ? value : null;
    }

    public double sum(ToDoubleFunction<T> selector) {
        if (parallel) {
            return stream().mapToDouble(selector).sum();
        }
        double sum = 0D;
        for (T item : data) {
            sum += selector.applyAsDouble(item);
        }
        return sum;
    }

    public Decimal sumDecimal(BiFunc<T, Decimal> selector) {
        if (parallel) {
            return stream().map(selector).reduce(Decimal.ZERO, Decimal::add);
        }
        Decimal sumValue = Decimal.ZERO;
        for (T item : data) {
            sumValue = sumValue.add(selector.apply(item));
        }
        return sumValue;
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
        Iterator<T> iterator = iterator();
        if (!iterator.hasNext()) {
            throw new NoSuchElementException("No value present");
        }
        return iterator.next();
    }

    public T first(PredicateFunc<T> predicate) {
        for (T item : data) {
            if (predicate.test(item)) {
                return item;
            }
        }
        throw new NoSuchElementException("No value present");
    }

    public T firstOrDefault() {
        return firstOrDefault((T) null);
    }

    public T firstOrDefault(T defaultValue) {
        Iterator<T> iterator = iterator();
        return iterator.hasNext() ? iterator.next() : defaultValue;
    }

    public T firstOrDefault(Func<T> defaultValue) {
        Iterator<T> iterator = iterator();
        return iterator.hasNext() ? iterator.next() : defaultValue.get();
    }

    public T firstOrDefault(PredicateFunc<T> predicate) {
        if (parallel) {
            return stream().filter(predicate).findFirst().orElse(null);
        }
        for (T item : data) {
            if (predicate.test(item)) {
                return item;
            }
        }
        return null;
    }

    public T last() {
        boolean found = false;
        T value = null;
        if (data instanceof List) {
            List<T> list = (List<T>) data;
            if (!list.isEmpty()) {
                return list.get(list.size() - 1);
            }
        } else {
            for (T datum : data) {
                value = datum;
                found = true;
            }
        }
        if (!found) {
            throw new NoSuchElementException("No value present");
        }
        return value;
    }

    public T last(PredicateFunc<T> predicate) {
        T value = null;
        boolean found = false;
        for (T item : data) {
            if (predicate.test(item)) {
                value = item;
                found = true;
            }
        }
        if (!found) {
            throw new NoSuchElementException("No value present");
        }
        return value;
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
        boolean found = false;
        T value = null;
        if (data instanceof List) {
            List<T> list = (List<T>) data;
            if (!list.isEmpty()) {
                return list.get(list.size() - 1);
            }
        } else {
            for (T datum : data) {
                value = datum;
                found = true;
            }
        }
        return found ? value : defaultValue;
    }

    public T lastOrDefault(PredicateFunc<T> predicate) {
        T value = null;
        boolean found = false;
        for (T item : data) {
            if (predicate.test(item)) {
                value = item;
                found = true;
            }
        }
        return found ? value : null;
    }

    public T single() {
        return single(null);
    }

    @ErrorCode
    public T single(PredicateFunc<T> predicate) {
        T result = null;
        int count = 0;
        for (T item : data) {
            if (predicate != null && !predicate.test(item)) {
                continue;
            }
            result = item;
            if (++count > 1) {
                break;
            }
        }
        if (count != 1) {
            throw new ApplicationException(values(count));
        }
        return result;
    }

    public T singleOrDefault() {
        return singleOrDefault(null);
    }

    @ErrorCode
    public T singleOrDefault(PredicateFunc<T> predicate) {
        T result = null;
        int count = 0;
        for (T item : data) {
            if (predicate != null && !predicate.test(item)) {
                continue;
            }
            result = item;
            if (++count > 1) {
                throw new ApplicationException(values(count));
            }
        }
        return count == 0 ? null : result;
    }

    public Linq<T> skip(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count");
        }
        if (parallel) {
            return me(stream().skip(count));
        }
        List<T> result = newList();
        int index = 0;
        for (T item : data) {
            if (index++ >= count) {
                result.add(item);
            }
        }
        return me(result);
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
        if (count < 0) {
            throw new IllegalArgumentException("count");
        }
        if (parallel) {
            return me(stream().limit(count));
        }
        List<T> result = newList(Math.min(sizeHint(), count));
        if (count == 0) {
            return me(result);
        }
        int index = 0;
        for (T item : data) {
            if (index++ >= count) {
                break;
            }
            result.add(item);
        }
        return me(result);
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
        Objects.requireNonNull(delimiter);
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        for (T item : data) {
            if (sb.length() != 0) {
                sb.append(delimiter);
            }
            sb.append(Objects.requireNonNull((CharSequence) item));
        }
        return sb.toString();
    }

    public String toJoinString(String delimiter, BiFunc<T, String> selector) {
        Objects.requireNonNull(delimiter);
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        for (T item : data) {
            if (sb.length() != 0) {
                sb.append(delimiter);
            }
            sb.append(Objects.requireNonNull(selector.apply(item)));
        }
        return sb.toString();
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
        for (T item : data) {
            result.put(keySelector.apply(item), resultSelector.apply(item));
        }
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
