package org.rx.test;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.junit.jupiter.api.Test;
import org.rx.annotation.ErrorCode;
import org.rx.beans.$;
import org.rx.beans.RandomList;
import org.rx.beans.Tuple;
import org.rx.core.*;
import org.rx.core.Arrays;
import org.rx.test.bean.*;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.beans.$.$;
import static org.rx.core.Contract.*;
import static org.rx.core.Contract.toJsonString;

@Slf4j
public class CoreTester {
    //region NQuery
    @Test
    public void runNQuery() {
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
                NQuery.of(personSet).groupByMany(p -> new Object[]{p.index2, p.index3}, p -> {
                    System.out.println("groupKey: " + toJsonString(p.left));
                    List<Person> list = p.right.toList();
                    System.out.println("items: " + toJsonString(list));
                    return list.get(0);
                }));

        showResult("orderBy(p->p.index)", NQuery.of(personSet).orderBy(p -> p.index));
        showResult("orderByDescending(p->p.index)", NQuery.of(personSet).orderByDescending(p -> p.index));
        showResult("orderByMany(p -> new Object[] { p.index2, p.index })",
                NQuery.of(personSet).orderByMany(p -> new Object[]{p.index2, p.index}));
        showResult("orderByDescendingMany(p -> new Object[] { p.index2, p.index })",
                NQuery.of(personSet).orderByDescendingMany(p -> new Object[]{p.index2, p.index}));

        showResult("select(p -> p.index).reverse()",
                NQuery.of(personSet).orderBy(p -> p.index).select(p -> p.index).reverse());

        showResult(".max(p -> p.index)", NQuery.of(personSet).<Integer>max(p -> p.index));
        showResult(".min(p -> p.index)", NQuery.of(personSet).<Integer>min(p -> p.index));

        showResult("take(0).average(p -> p.index)", NQuery.of(personSet).take(0).average(p -> p.index));
        showResult("average(p -> p.index)", NQuery.of(personSet).average(p -> p.index));
        showResult("take(0).sum(p -> p.index)", NQuery.of(personSet).take(0).sum(p -> p.index));
        showResult("sum(p -> p.index)", NQuery.of(personSet).sum(p -> p.index));

        showResult("cast<IPerson>", NQuery.of(personSet).<IPerson>cast());
        NQuery oq = NQuery.of(personSet).cast().union(Arrays.toList(1, 2, 3));
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
        public int index;
        public int index2;
        public int index3;
        public String name;
        public int age;
    }
    //endregion

    @SneakyThrows
    @Test
    public void threadPool() {
        ThreadPool.DynamicConfig config = new ThreadPool.DynamicConfig();
//        config.setMaxThreshold(1);
        ExecutorService pool = new ThreadPool(1, 1, 1, 8, "RxPool")
                .statistics(config);
        for (int i = 0; i < 100; i++) {
            int n = i;
            pool.execute(() -> {
                log.info("exec {} begin..", n);
                App.sleep(15 * 1000);
                log.info("exec {} end..", n);
            });
        }

//        ExecutorService es1 = Executors.newCachedThreadPool();
//        ExecutorService es2 = Executors.newFixedThreadPool(ThreadPool.CpuThreads);
//        ExecutorService es3 = Executors.newFixedThreadPool(10);

        System.out.println("main thread done");
        System.in.read();
    }

    @SneakyThrows
    @Test
    public void reflect() {
        ErrorBean bean = Reflects.newInstance(ErrorBean.class, 0, null);
        System.out.println(bean.getError());

        Reflects.invokeMethod(ErrorBean.class, null, "theStatic", 0, null);
        Object v = MethodUtils.invokeMethod(bean, true, "theMethod", 0, null);
        System.out.println(bean.getError());
    }

    @Test
    public void fluentWait() {
        FluentWait.newInstance(2000, 200).until(s -> {
            System.out.println(System.currentTimeMillis());
            return false;
        });
    }

    @Test
    public void weakCache() {
        WeakCache<String, Object> cache = WeakCache.getInstance();
        String k = "a";
        cache.add(k, new Object());
        assert cache.get(k) != null;
        System.out.println(cache.size());

        System.gc();

        assert cache.get(k) == null;
        System.out.println(cache.size());
    }

    @Test
    public void randomList() {
        NQuery<Integer> q = NQuery.of(1, 2);
        System.out.println(q.toArray());

//        RandomList<String> wr = new RandomList<>();
//        wr.add("a", 5);
//        wr.add("b", 2);
//        wr.add("c", 3);
//        for (int i = 0; i < 20; i++) {
//            System.out.println(wr.next());
//        }
    }

    @Test
    @ErrorCode(messageKeys = {"$x"})
    @ErrorCode(cause = IllegalArgumentException.class, messageKeys = {"$x"})
    public void exceptionCode() {
        String val = "rx";
        SystemException ex = new SystemException(values(val));
        assert eq(ex.getFriendlyMessage(), "Default Error Code value=" + val);

        ex = new SystemException(values(val), new IllegalArgumentException());
        assert eq(ex.getFriendlyMessage(), "Exception Error Code value=" + val);
        $<IllegalArgumentException> out = $();
        assert ex.tryGet(out, IllegalArgumentException.class);

        String uid = "userId";
        ex.setErrorCode(UserManager.BizCode.argument, uid);
        assert eq(ex.getFriendlyMessage(), "Enum Error Code value=" + uid);

        String date = "2017-08-24 02:02:02";
        assert App.changeType(date, Date.class) instanceof Date;
        try {
            date = "x";
            App.changeType(date, Date.class);
        } catch (SystemException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void shorterUUID() {
        UUID id = UUID.randomUUID();
        String sid = App.toShorterUUID(id);
        UUID id2 = App.fromShorterUUID(sid);
        System.out.println(sid);
        assert id.equals(id2);
    }

    @Test
    public void readSetting() {
        Map<String, Object> map = App.loadYaml("application.yml");
        System.out.println(map);
        Object v = App.readSetting("app.test.version");
        assert v.equals(0);
        v = App.readSetting("not");
        assert v == null;

        v = App.readSetting("org.rx.test.CoreTester", null, App.loadYaml(SystemException.CodeFile));
        assert v instanceof Map;

        v = App.readSetting("org.rx.test.CoreTester.testCode<IllegalArgumentException>", null, App.loadYaml(SystemException.CodeFile));
        assert eq(v, "Exception Error Code value=$x");
    }
}
