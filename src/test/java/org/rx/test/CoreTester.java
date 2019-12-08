package org.rx.test;

import com.google.common.reflect.TypeToken;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.junit.jupiter.api.Test;
import org.rx.annotation.ErrorCode;
import org.rx.beans.$;
import org.rx.beans.DateTime;
import org.rx.beans.RandomList;
import org.rx.beans.Tuple;
import org.rx.core.*;
import org.rx.core.Arrays;
import org.rx.test.bean.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

import static org.rx.beans.$.$;
import static org.rx.core.Contract.*;
import static org.rx.core.Contract.toJsonString;

@Slf4j
public class CoreTester {
    //region NQuery
    @SneakyThrows
    @Test
    public void runParallelNQuery() {
        ManagementMonitor.getInstance().scheduled = (s, e) -> {
            System.out.println(toJsonString(e.getValue()));
        };

        for (Integer integer : NQuery.of(Arrays.toList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), true)
                .groupBy(p -> p > 5, (p, x) -> x.first())) {
            System.out.println(integer.toString());
        }
        System.in.read();
    }

    @Test
    public void runNQuery() {
        Set<PersonInfo> personSet = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            PersonInfo p = new PersonInfo();
            p.index = i;
            p.index2 = i % 2 == 0 ? 2 : i;
            p.index3 = i % 2 == 0 ? 3 : 4;
            p.name = Strings.randomValue(5);
            p.age = ThreadLocalRandom.current().nextInt(100);
            personSet.add(p);
        }
        PersonInfo px = new PersonInfo();
        px.index2 = 2;
        px.index3 = 41;
        personSet.add(px);

        showResult("leftJoin", NQuery.of(new PersonInfo(27, 27, 27, "jack", 6, DateTime.now()),
                new PersonInfo(28, 28, 28, "tom", 6, DateTime.now()),
                new PersonInfo(29, 29, 29, "lily", 8, DateTime.now()),
                new PersonInfo(30, 30, 30, "cookie", 6, DateTime.now())).leftJoin(
                Arrays.toList(new PersonInfo(27, 27, 27, "cookie", 5, DateTime.now()),
                        new PersonInfo(28, 28, 28, "tom", 10, DateTime.now()),
                        new PersonInfo(29, 29, 29, "jack", 1, DateTime.now()),
                        new PersonInfo(30, 30, 30, "session", 25, DateTime.now()),
                        new PersonInfo(31, 31, 31, "trump", 55, DateTime.now()),
                        new PersonInfo(32, 32, 32, "jack", 55, DateTime.now())), (p, x) -> p.name.equals(x.name), Tuple::of
        ));

        showResult("groupBy(p -> p.index2...", NQuery.of(personSet).groupBy(p -> p.index2, (p, x) -> {
            System.out.println("groupKey: " + p);
            List<PersonInfo> list = x.toList();
            System.out.println("items: " + Contract.toJsonString(list));
            return list.get(0);
        }));
        showResult("groupByMany(p -> new Object[] { p.index2, p.index3 })",
                NQuery.of(personSet).groupByMany(p -> new Object[]{p.index2, p.index3}, (p, x) -> {
                    System.out.println("groupKey: " + toJsonString(p));
                    List<PersonInfo> list = x.toList();
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
    //endregion

    @Test
    public void runNEvent() {
        UserManagerImpl mgr = new UserManagerImpl();
        PersonInfo p = new PersonInfo();
        p.index = 1;
        p.name = "rx";
        p.age = 6;

        BiConsumer<UserManager, UserEventArgs> a = (s, e) -> System.out.println("a:" + e);
        BiConsumer<UserManager, UserEventArgs> b = (s, e) -> System.out.println("b:" + e);

        mgr.onAddUser = a;
        mgr.addUser(p);  //触发事件（a执行）

        mgr.onAddUser = combine(mgr.onAddUser, b);
        mgr.addUser(p); //触发事件（a, b执行）

        mgr.onAddUser = remove(mgr.onAddUser, b);
        mgr.addUser(p); //触发事件（b执行）
    }

    @SneakyThrows
    @Test
    public void threadPool() {
        //Executors.newCachedThreadPool(); 没有queue缓冲，一直new thread执行，当cpu负载高时加上更多线程上下文切换损耗，性能会急速下降。

        //Executors.newFixedThreadPool(16); 执行的thread数量固定，但当thread 等待时间（IO时间）过长时会造成吞吐量下降。当thread 执行时间过长时无界的LinkedBlockingQueue可能会OOM。

        //new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(10000));
        //有界的LinkedBlockingQueue可以避免OOM，但吞吐量下降的情况避免不了，加上LinkedBlockingQueue使用的重量级锁ReentrantLock对并发下性能可能有影响

        //最佳线程数=CPU 线程数 * (1 + CPU 等待时间 / CPU 执行时间)，由于执行任务的不同，CPU 等待时间和执行时间无法确定，
        //因此换一种思路，当列队满的情况下，如果CPU使用率小于40%，则会动态增大线程池maxThreads 最大线程数的值来提高吞吐量。如果CPU使用率大于60%，则会动态减小maxThreads 值来降低生产者的任务生产速度。
        ThreadPool.DynamicConfig config = new ThreadPool.DynamicConfig();
        config.setMinThreshold(40);
        config.setMaxThreshold(60);
        //当最小线程数的线程量处理不过来的时候，会创建到最大线程数的线程量来执行。当最大线程量的线程执行不过来的时候，会把任务丢进列队，当列队满的时候会阻塞当前线程，降低生产者的生产速度。
        //LinkedTransferQueue基于CAS实现，性能比LinkedBlockingQueue要好。
        ExecutorService pool = new ThreadPool(1, 1, 1, 8, "RxPool")
                .statistics(config);
        for (int i = 0; i < 100; i++) {
            int n = i;
            pool.execute(() -> {
                log.info("exec {} begin..", n);
                sleep(15 * 1000);
                log.info("exec {} end..", n);
            });
        }

        System.out.println("main thread done");
        System.in.read();
    }

    @Test
    public void fluentWait() {
        FluentWait.newInstance(2000, 200).until(s -> {
            System.out.println(System.currentTimeMillis());
            return false;
        });
    }

    @Test
    public void randomList() {
        RandomList<String> wr = new RandomList<>();
        wr.add("a", 5);
        wr.add("b", 2);
        wr.add("c", 3);
        for (int i = 0; i < 20; i++) {
            System.out.println(wr.next());
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

    @Test
    public void json() {
        String str = "abc";
        assert str.equals(toJsonString(str));
        String jObj = toJsonString(PersonInfo.def);
        System.out.println("jObj: " + jObj);
        assert fromJsonAsObject(jObj, PersonInfo.class).equals(PersonInfo.def);
        List<PersonInfo> arr = Arrays.toList(PersonInfo.def, PersonInfo.def);
        String jArr = toJsonString(arr);
        System.out.println("jArr: " + jArr);
        assert ListUtils.isEqualList(fromJsonAsList(jArr, PersonInfo.class), arr);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "rocky");
        data.put("age", 10);
        data.put("date", DateTime.now());
        System.out.println(toJsonString(data));

        List<PersonInfo> list = fromJsonAsList(jArr, PersonInfo.class);
        for (PersonInfo info : list) {
            System.out.println(info);
        }
        list = fromJsonAsList(arr, PersonInfo.class);
        for (PersonInfo info : list) {
            System.out.println(info);
        }

        Tuple<String, List<Float>> tuple = Tuple.of("abc", Arrays.toList(1.2F, 2F, 3F));
        String tjObj = toJsonString(tuple);
        tuple = fromJsonAsObject(tjObj, new TypeToken<Tuple<String, List<Float>>>() {
        }.getType());
        System.out.println(tuple);

        List<Tuple<String, List<Float>>> tupleList = Arrays.toList(Tuple.of("abc", Arrays.toList(1.2F, 2F, 3F)), Tuple.of("def", Arrays.toList(1.2F, 2F, 3F)));
        String tjArr = toJsonString(tupleList);
        tupleList = fromJsonAsList(tjArr, new TypeToken<List<Tuple<String, List<Float>>>>() {
        }.getType());
        for (Tuple<String, List<Float>> stringListTuple : tupleList) {
            System.out.println(stringListTuple);
        }
        tupleList = fromJsonAsList(tupleList, new TypeToken<List<Tuple<String, List<Float>>>>() {
        }.getType());
        for (Tuple<String, List<Float>> stringListTuple : tupleList) {
            System.out.println(stringListTuple);
        }
    }
}
