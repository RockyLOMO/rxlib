package org.rx.core;

import org.junit.jupiter.api.Test;
import org.rx.bean.Decimal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinqTest {
    @Test
    void fromStreamShouldBeReusable() {
        Linq<Integer> q = Linq.from(Stream.of(1, 2, 3));

        assertEquals(Arrays.asList(1, 2, 3), q.toList());
        assertEquals(Arrays.asList(2, 4, 6), q.select(p -> p * 2).toList());
        assertEquals(3, q.count());
        assertEquals(Integer.valueOf(1), q.firstOrDefault());
    }

    @Test
    void fromSinglePassIterableShouldBeReusable() {
        AtomicInteger iteratorCalls = new AtomicInteger();
        Iterable<Integer> source = () -> {
            if (iteratorCalls.incrementAndGet() > 1) {
                throw new IllegalStateException("source iterable reused");
            }
            return Arrays.asList(1, 2, 3).iterator();
        };

        Linq<Integer> q = Linq.from(source);

        assertEquals(3, q.count());
        assertEquals(Integer.valueOf(1), q.firstOrDefault());
        assertEquals(1, iteratorCalls.get());
    }

    @Test
    void orderByShouldCacheSortKey() {
        AtomicInteger calls = new AtomicInteger();

        List<Integer> result = Linq.from(3, 1, 2).orderBy(p -> {
            calls.incrementAndGet();
            return p;
        }).toList();

        assertEquals(Arrays.asList(1, 2, 3), result);
        assertEquals(3, calls.get());
    }

    @Test
    void parallelSelectShouldKeepParallelFlag() {
        Linq<Integer> q = Linq.from(Arrays.asList(1, 2, 3, 4), true).select(p -> p * 2);

        assertTrue(q.stream().isParallel());
        assertEquals(Arrays.asList(2, 4, 6, 8), q.orderBy(p -> p).toList());
    }

    @Test
    void leftJoinShouldSupportSinglePassRightIterable() {
        AtomicInteger iteratorCalls = new AtomicInteger();
        Iterable<Integer> right = () -> {
            if (iteratorCalls.incrementAndGet() > 1) {
                throw new IllegalStateException("right iterable reused");
            }
            return Arrays.asList(2, 3).iterator();
        };

        List<String> result = Linq.from(1, 2, 3)
                .leftJoin(right, (left, item) -> Objects.equals(left, item), (left, item) -> left + ":" + item)
                .toList();

        assertEquals(Arrays.asList("1:null", "2:2", "3:3"), result);
        assertEquals(1, iteratorCalls.get());
    }

    @Test
    void reverseShouldReverseEncounterOrder() {
        assertEquals(Arrays.asList(3, 1, 2), Linq.from(2, 1, 3).reverse().toList());
    }

    @Test
    void orderByRandShouldNotMutateSourceList() {
        List<Integer> source = new ArrayList<>(Arrays.asList(1, 2, 3, 4));

        Linq.from(source).orderByRand();

        assertEquals(Arrays.asList(1, 2, 3, 4), source);
    }

    @Test
    void parallelSumDecimalShouldBeCorrect() {
        List<Integer> source = Stream.iterate(1, p -> p + 1).limit(1000).collect(Collectors.toList());

        Decimal sum = Linq.from(source, true).sumDecimal(p -> Decimal.valueOf(1D));

        assertEquals(Decimal.valueOf(1000D), sum);
    }

    @Test
    void firstAndLastShouldPreserveNullElements() {
        Linq<Integer> q = Linq.from(Arrays.asList(null, 1, null));

        assertNull(q.first(p -> true));
        assertNull(q.last());
        assertNull(q.lastOrDefault(99));
    }

    @Test
    void unionAndDifferenceShouldKeepBagSemantics() {
        Linq<Integer> left = Linq.from(1, 1, 2);
        List<Integer> right = Arrays.asList(1, 2, 2, 3);

        assertEquals(Arrays.asList(1, 1, 2, 2, 3), left.union(right).toList());
        assertEquals(Arrays.asList(1, 2, 3), left.difference(right).toList());
    }

    @Test
    void toJoinStringShouldSupportSelectorWithoutIntermediateQuery() {
        String value = Linq.from(1, 2, 3).toJoinString("-", p -> "[" + p + "]");

        assertEquals("[1]-[2]-[3]", value);
    }

    @Test
    void parallelForEachOrderedShouldRespectEncounterOrder() {
        List<Integer> result = new ArrayList<>();

        Linq.from(Arrays.asList(1, 2, 3, 4), true).forEachOrdered(result::add);

        assertEquals(Arrays.asList(1, 2, 3, 4), result);
    }

    @Test
    void groupByManyShouldMergeEquivalentKeyLists() {
        List<Integer> result = Linq.from(1, 2, 3, 4)
                .groupByMany(p -> Arrays.<Object>asList(p % 2, p > 2), (key, items) -> items.count())
                .orderBy(p -> p)
                .toList();

        assertEquals(Arrays.asList(1, 1, 1, 1), result);
    }
}
