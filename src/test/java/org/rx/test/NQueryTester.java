package org.rx.test;

import org.junit.jupiter.api.Test;
import org.rx.core.Contract;
import org.rx.core.NQuery;
import org.rx.core.Strings;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.core.Contract.toJsonString;

public class NQueryTester {
    @Test
    public void run() {
        Set<Person> personSet = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            Person p = new Person();
            p.index = i;
            p.index2 = i % 2 == 0 ? 2 : i;
            p.index3 = i % 2 == 0 ? 3 : 4;
            p.name = Strings.randomValue(5);
            p.age = ThreadLocalRandom.current().nextInt(100);
            personSet.add(p);
        }
        Person px = new Person();
        px.index2 = 2;
        px.index3 = 41;
        personSet.add(px);

        showResult("groupBy(p -> p.index2...", NQuery.of(personSet).groupBy(p -> p.index2, p -> {
            System.out.println("groupKey: " + p.left);
            List<Person> list = p.right.toList();
            System.out.println("items: " + Contract.toJsonString(list));
            return list.get(0);
        }));
        showResult("groupByMany(p -> new Object[] { p.index2, p.index3 })",
                NQuery.of(personSet).groupByMany(p -> new Object[] { p.index2, p.index3 }, p -> {
                    System.out.println("groupKey: " + toJsonString(p.left));
                    List<Person> list = p.right.toList();
                    System.out.println("items: " + toJsonString(list));
                    return list.get(0);
                }));

        showResult("orderBy(p->p.index)", NQuery.of(personSet).orderBy(p -> p.index));
        showResult("orderByDescending(p->p.index)", NQuery.of(personSet).orderByDescending(p -> p.index));
        showResult("orderByMany(p -> new Object[] { p.index2, p.index })",
                NQuery.of(personSet).orderByMany(p -> new Object[] { p.index2, p.index }));
        showResult("orderByDescendingMany(p -> new Object[] { p.index2, p.index })",
                NQuery.of(personSet).orderByDescendingMany(p -> new Object[] { p.index2, p.index }));

        showResult("select(p -> p.index).reverse()",
                NQuery.of(personSet).orderBy(p -> p.index).select(p -> p.index).reverse());

        showResult(".max(p -> p.index)", NQuery.of(personSet).<Integer> max(p -> p.index));
        showResult(".min(p -> p.index)", NQuery.of(personSet).<Integer> min(p -> p.index));

        showResult("take(0).average(p -> p.index)", NQuery.of(personSet).take(0).average(p -> p.index));
        showResult("average(p -> p.index)", NQuery.of(personSet).average(p -> p.index));
        showResult("take(0).sum(p -> p.index)", NQuery.of(personSet).take(0).sum(p -> p.index));
        showResult("sum(p -> p.index)", NQuery.of(personSet).sum(p -> p.index));

        showResult("cast<IPerson>", NQuery.of(personSet).<IPerson> cast());
        NQuery oq = NQuery.of(personSet).cast().union(Arrays.asList(1, 2, 3));
        showResult("ofType(Integer.class)", oq.ofType(Integer.class));

        showResult("firstOrDefault()", NQuery.of(personSet).orderBy(p -> p.index).firstOrDefault());
        showResult("lastOrDefault()", NQuery.of(personSet).orderBy(p -> p.index).lastOrDefault());
        showResult("skip(2)", NQuery.of(personSet).orderBy(p -> p.index).skip(2));
        showResult("take(2)", NQuery.of(personSet).orderBy(p -> p.index).take(2));

        showResult(".skipWhile((p, i) -> p.index < 3)",
                NQuery.of(personSet).orderBy(p -> p.index).skipWhile((p, i) -> p.index < 3));

        showResult(".takeWhile((p, i) -> p.index < 3)",
                NQuery.of(personSet).orderBy(p -> p.index).takeWhile((p, i) -> p.index < 3));
    }

    private void showResult(String n, Object q) {
        System.out.println();
        System.out.println();
        System.out.println("showResult: " + n);
        System.out.println(Contract.toJsonString(q));
    }

    private void showResult(String n, NQuery q) {
        System.out.println();
        System.out.println();
        System.out.println("showResult: " + n);
        System.out.println(Contract.toJsonString(q.toList()));
    }

    public interface IPerson {

    }

    public static class Person implements IPerson {
        public int    index;
        public int    index2;
        public int    index3;
        public String name;
        public int    age;
    }
}
