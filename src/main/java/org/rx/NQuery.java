package org.rx;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Streams;
import org.rx.bean.Tuple;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.rx.Contract.*;

/**
 * https://msdn.microsoft.com/en-us/library/bb738550(v=vs.110).aspx
 * 
 * @param <T>
 */
public final class NQuery<T> implements Iterable<T> {
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

    //region of
    private static final Comparator NaturalOrder = Comparator.naturalOrder(), ReverseOrder = Comparator.reverseOrder();

    public static <T> NQuery<T> of(T... args) {
        return of(Arrays.asList(args));
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
    private Collection current;
    private boolean    isParallel;

    public Stream<T> stream() {
        return isParallel ? current.parallelStream() : current.stream();
    }

    private NQuery(Collection<T> set, boolean isParallel) {
        this.current = set;
        this.isParallel = isParallel;
    }

    @Override
    public Iterator<T> iterator() {
        return stream().iterator();
    }

    private <TR> List<TR> newList() {
        return isParallel ? Collections.synchronizedList(new ArrayList<>()) : new ArrayList<>();
    }

    private <TR> Set<TR> newSet() {
        return isParallel ? ConcurrentHashMap.newKeySet() : new HashSet<>();
    }

    private <TK, TR> Map<TK, TR> newMap() {
        return isParallel ? new ConcurrentHashMap<>() : new HashMap<>();
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
        return me(StreamSupport.stream(
                new Spliterators.AbstractSpliterator<TR>(spliterator.estimateSize(), spliterator.characteristics()) {
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
        int None   = 0;
        int Accept = 1;
        int Break  = 1 << 1;
        int All    = Accept | Break;

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
        List<TR> result = newList();
        AtomicInteger counter = new AtomicInteger();
        stream().forEach(t -> result.add(selector.apply(t, counter.getAndIncrement())));
        return me(result);
    }

    public <TR> NQuery<TR> selectMany(Function<T, Collection<TR>> selector) {
        return me(stream().flatMap(p -> newStream(selector.apply(p))));
    }

    public <TR> NQuery<TR> selectMany(IndexSelector<T, Collection<TR>> selector) {
        List<TR> result = newList();
        AtomicInteger counter = new AtomicInteger();
        stream().forEach(t -> newStream(selector.apply(t, counter.getAndIncrement())).forEach(result::add));
        return me(result);
    }

    public NQuery<T> where(Predicate<T> predicate) {
        return me(stream().filter(predicate));
    }

    public NQuery<T> where(IndexPredicate<T> predicate) {
        List<T> result = newList();
        AtomicInteger counter = new AtomicInteger();
        stream().forEach(t -> {
            if (!predicate.test(t, counter.getAndIncrement())) {
                return;
            }
            result.add(t);
        });
        return me(result);
    }

    public <TI, TR> NQuery<TR> join(Collection<TI> inner, BiPredicate<T, TI> keySelector,
                                    BiFunction<T, TI, TR> resultSelector) {
        return me(stream().flatMap(
                p -> newStream(inner).filter(p2 -> keySelector.test(p, p2)).map(p3 -> resultSelector.apply(p, p3))));
    }

    public <TI, TR> NQuery<TR> join(Function<T, TI> innerSelector, BiPredicate<T, TI> keySelector,
                                    BiFunction<T, TI, TR> resultSelector) {
        List<TI> inner = newList();
        stream().forEach(t -> inner.add(innerSelector.apply(t)));
        return join(inner, keySelector, resultSelector);
    }

    public <TI, TR> NQuery<TR> joinMany(Function<T, Collection<TI>> innerSelector, BiPredicate<T, TI> keySelector,
                                        BiFunction<T, TI, TR> resultSelector) {
        List<TI> inner = newList();
        stream().forEach(t -> newStream(innerSelector.apply(t)).forEach(inner::add));
        return join(inner, keySelector, resultSelector);
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

    public NQuery<T> concat(Collection<T> set) {
        return me(Stream.concat(stream(), newStream(set)));
    }

    public NQuery<T> distinct() {
        return me(stream().distinct());
    }

    public NQuery<T> except(Collection<T> set) {
        return me(stream().filter(p -> !newStream(set).anyMatch(p2 -> p2.equals(p))));
    }

    public NQuery<T> intersect(Collection<T> set) {
        return me(stream().filter(p -> newStream(set).anyMatch(p2 -> p2.equals(p))));
    }

    public NQuery<T> union(Collection<T> set) {
        return concat(set);
    }

    public <TK> NQuery<T> orderBy(Function<T, TK> keySelector) {
        return me(stream().sorted(getComparator(keySelector)));
    }

    private <TK> Comparator<T> getComparator(Function<T, TK> keySelector) {
        return (p1, p2) -> {
            Comparable c1 = as(keySelector.apply(p1), Comparable.class);
            if (c1 == null) {
                return 0;
            }
            return c1.compareTo(keySelector.apply(p2));
        };
    }

    public <TK> NQuery<T> orderByDescending(Function<T, TK> keySelector) {
        return me(stream().sorted(getComparator(keySelector).reversed()));
    }

    public NQuery<T> orderByMany(Function<T, Object[]> keySelector) {
        return me(stream().sorted(getComparatorMany(keySelector)));
    }

    private Comparator<T> getComparatorMany(Function<T, Object[]> keySelector) {
        return (p1, p2) -> {
            Object[] k1s = keySelector.apply(p1);
            Object[] k2s = keySelector.apply(p2);
            for (int i = 0; i < k1s.length; i++) {
                Comparable c1 = as(k1s[i], Comparable.class);
                if (c1 == null) {
                    continue;
                }
                int r = c1.compareTo(k2s[i]);
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

    public <TK, TR> NQuery<TR> groupBy(Function<T, TK> keySelector, Function<Tuple<TK, NQuery<T>>, TR> resultSelector) {
        Map<TK, List<T>> map = newMap();
        stream().forEach(t -> map.computeIfAbsent(keySelector.apply(t), p -> newList()).add(t));
        List<TR> result = newList();
        for (Map.Entry<TK, List<T>> entry : map.entrySet()) {
            result.add(resultSelector.apply(Tuple.of(entry.getKey(), of(entry.getValue()))));
        }
        return me(result);
    }

    public <TR> NQuery<TR> groupByMany(Function<T, Object[]> keySelector,
                                       Function<Tuple<Object[], NQuery<T>>, TR> resultSelector) {
        Map<String, Tuple<Object[], List<T>>> map = newMap();
        stream().forEach(t -> {
            Object[] ks = keySelector.apply(t);
            map.computeIfAbsent(toJSONString(ks), p -> Tuple.of(ks, newList())).right.add(t);
        });
        List<TR> result = newList();
        for (Tuple<Object[], List<T>> entry : map.values()) {
            result.add(resultSelector.apply(Tuple.of(entry.left, of(entry.right))));
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

    @ErrorCode(messageKeys = { "$count" })
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

    @ErrorCode(messageKeys = { "$count" })
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

    public <TK, TR> Map<TK, TR> toMap(Function<T, TK> keySelector, Function<T, TR> resultSelector) {
        return isParallel ? stream().collect(Collectors.toConcurrentMap(keySelector, resultSelector))
                : stream().collect(Collectors.toMap(keySelector, resultSelector));
    }

    public static void main(String[] args) {
        Set<Person> personSet = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            Person p = new Person();
            p.index = i;
            p.index2 = i % 2 == 0 ? 2 : i;
            p.index3 = i % 2 == 0 ? 3 : 4;
            p.name = App.randomString(5);
            p.age = ThreadLocalRandom.current().nextInt(100);
            personSet.add(p);
        }
        Person px = new Person();
        px.index2 = 2;
        px.index3 = 41;
        personSet.add(px);

        showResult("groupBy(p -> p.index2...", of(personSet).groupBy(p -> p.index2, p -> {
            System.out.println("groupKey: " + p.left);
            List<Person> list = p.right.toList();
            System.out.println("items: " + JSON.toJSONString(list));
            return list.get(0);
        }));
        showResult("groupByMany(p -> new Object[] { p.index2, p.index3 })",
                of(personSet).groupByMany(p -> new Object[] { p.index2, p.index3 }, p -> {
                    System.out.println("groupKey: " + toJSONString(p.left));
                    List<Person> list = p.right.toList();
                    System.out.println("items: " + toJSONString(list));
                    return list.get(0);
                }));

        showResult("orderBy(p->p.index)", of(personSet).orderBy(p -> p.index));
        showResult("orderByDescending(p->p.index)", of(personSet).orderByDescending(p -> p.index));
        showResult("orderByMany(p -> new Object[] { p.index2, p.index })",
                of(personSet).orderByMany(p -> new Object[] { p.index2, p.index }));
        showResult("orderByDescendingMany(p -> new Object[] { p.index2, p.index })",
                of(personSet).orderByDescendingMany(p -> new Object[] { p.index2, p.index }));

        showResult("select(p -> p.index).reverse()",
                of(personSet).orderBy(p -> p.index).select(p -> p.index).reverse());

        showResult(".max(p -> p.index)", of(personSet).<Integer> max(p -> p.index));
        showResult(".min(p -> p.index)", of(personSet).<Integer> min(p -> p.index));

        showResult("take(0).average(p -> p.index)", of(personSet).take(0).average(p -> p.index));
        showResult("average(p -> p.index)", of(personSet).average(p -> p.index));
        showResult("take(0).sum(p -> p.index)", of(personSet).take(0).sum(p -> p.index));
        showResult("sum(p -> p.index)", of(personSet).sum(p -> p.index));

        showResult("firstOrDefault()", of(personSet).orderBy(p -> p.index).firstOrDefault());
        showResult("lastOrDefault()", of(personSet).orderBy(p -> p.index).lastOrDefault());
        showResult("skip(2)", of(personSet).orderBy(p -> p.index).skip(2));
        showResult("take(2)", of(personSet).orderBy(p -> p.index).take(2));

        showResult(".skipWhile((p, i) -> p.index < 3)",
                of(personSet).orderBy(p -> p.index).skipWhile((p, i) -> p.index < 3));

        showResult(".takeWhile((p, i) -> p.index < 3)",
                of(personSet).orderBy(p -> p.index).takeWhile((p, i) -> p.index < 3));
    }

    private static void showResult(String n, Object q) {
        System.out.println();
        System.out.println();
        System.out.println("showResult: " + n);
        System.out.println(JSON.toJSONString(q));
    }

    private static void showResult(String n, NQuery q) {
        System.out.println();
        System.out.println();
        System.out.println("showResult: " + n);
        System.out.println(JSON.toJSONString(q.toList()));
    }

    public static class Person {
        public int    index;
        public int    index2;
        public int    index3;
        public String name;
        public int    age;
    }
}
