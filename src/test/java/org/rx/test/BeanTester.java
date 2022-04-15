package org.rx.test;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.bean.*;
import org.rx.core.Arrays;
import org.rx.core.StringBuilder;
import org.rx.core.Tasks;
import org.rx.test.bean.PersonBean;
import org.rx.test.bean.PersonGender;
import org.rx.test.common.TestUtil;

import java.util.*;
import java.util.concurrent.CountDownLatch;

import static org.rx.core.App.*;
import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.sleep;

@Slf4j
public class BeanTester extends TestUtil {
    @SneakyThrows
    @Test
    public void randomList() {
        RandomList<String> list = new RandomList<>();
        list.setSortFunc(p -> p);
        list.add("e", 0);
        list.add("a", 4);
        list.add("b", 3);
        list.add("c", 2);
        list.add("d", 1);
        list.removeIf(p -> p.equals("c"));
        //basic function
        for (String s : list) {
            System.out.print(s + " ");
        }
        System.out.println();
        for (int i = 0; i < 1000; i++) {
            assert !list.next().equals("e");
        }
        //concurrent function
        CountDownLatch l = new CountDownLatch(10);
        for (int i = 0; i < 10; i++) {
            Tasks.run(() -> {
                StringBuilder str = new StringBuilder();
                for (int j = 0; j < 10; j++) {
                    str.append(list.next()).append(" ");
                }
                System.out.println(str);
                l.countDown();
            });
        }
        l.await();
        for (int i = 0; i < 1000; i++) {
            Tasks.run(() -> {
                String next = list.next();
                System.out.println(next);
                list.remove(next);
                list.add(next);
            });
        }
        //steering function
        Object steeringKey = 1;
        int ttl = 2;
        String next = list.next(steeringKey, ttl);
        assert next.equals(list.next(steeringKey, ttl));
        log.info("steering {} -> {}", steeringKey, next);
        sleep(5000);
        String after = list.next(steeringKey, ttl);
        log.info("steering {} -> {} | {}", steeringKey, next, after);
        assert after.equals(list.next(steeringKey, ttl));
    }

    @Test
    public void dataTable() {
        DataTable dt = new DataTable();
        dt.addColumns("id", "name", "age");
        dt.addRow(1, "张三", 5);
        DataRow secondRow = dt.addRow(2, "李四", 10);
        DataRow row = dt.newRow(3, "湵范", 20);
        dt.setFluentRows(Arrays.toList(row).iterator());
        System.out.println(dt);

        dt.removeColumn("age");
        dt.addColumn("money");
        secondRow.set(2, 100);
        System.out.println(dt);

        System.out.println(toJsonString(dt));
    }

    @Test
    public void ushortPair() {
        UShortPair x = new UShortPair();
        System.out.println(x.getShort0());
        System.out.println(x.getShort1());
        int c = 2;
        for (int i = 0; i < 10; i++) {
            x.addShort0(c);
            x.addShort1(c);
            int j = c + i * c;
            System.out.println(x.getShort0());
            System.out.println(x.getShort1());
            assert (x.getShort0() == j);
            assert (x.getShort1() == j);
        }
    }

    @Test
    public void suid() {
        String jstr = toJsonString(SUID.randomSUID());
        SUID sid = fromJson(jstr, SUID.class);
        assert jstr.substring(1, jstr.length() - 1).equals(sid.toString());

        SUID suid = SUID.compute(TConfig.NAME_WYF);
        System.out.println(suid);

        SUID valueOf = SUID.valueOf(suid.toString());
        System.out.println(valueOf);

        assert suid.equals(valueOf);

        Set<SUID> set = new HashSet<>();
        int len = 100;  //1530ms
        invoke("suid", i -> {
            SUID suid1 = SUID.randomSUID();
            System.out.println(suid1);
            set.add(suid1);

            assert SUID.valueOf(suid1.toString()).equals(suid1);
        }, len);
        assert set.size() == len;
    }

    @Test
    public void decimal() {
        Decimal permille = Decimal.valueOf("50%");
        Decimal permille1 = Decimal.valueOf("500‰");
        Decimal permille2 = Decimal.valueOf(0.5);
        assert permille.equals(permille1) && permille1.equals(permille2);
        System.out.println(permille1);
        System.out.println(permille1.toPermilleString());
        System.out.println(permille1.toPermilleInt());
        System.out.println(permille1.toPercentString());
        System.out.println(permille1.toPercentInt());

        Decimal cent = Decimal.fromCent(1L);
        assert cent.compareTo(0.01) == 0;
        assert cent.eq(0.01);
        assert cent.ge(0.01);
        assert cent.le(0.01);
        assert cent.gt(-1);
        assert cent.le(1);
        assert cent.negate().eq(-0.01);
        assert cent.toCent() == 1;

        cent = cent.add(1.1);
        assert cent.compareTo(1.11) == 0;
        assert cent.toCent() == 111;

        String j = toJsonString(PersonBean.LeZhi);
        System.out.println(j);
        PersonBean d = fromJson(j, PersonBean.class);
        System.out.println(d);
    }

    @Test
    public void dateTime() {
        DateTime now = DateTime.now();
        DateTime utc = DateTime.utcNow();
        DateTime d = new DateTime(2010, 8, 24, 11, 12, 13);
        DateTime d3 = new DateTime(2010, 8, 23, 11, 12, 13);

        assert now.getTime() == utc.getTime();
        assert d.getYear() == 2010;
        assert d.getMonth() == 8;
        assert d.getDay() == 24;

        DateTime d2 = d.addYears(1);
        assert d.getYear() == 2010;
        assert d2.getYear() == 2011;
        assert d.subtract(d3).getTotalHours() == 24;

        System.out.println(now);
        System.out.println(utc);
        System.out.println(d.toDateTimeString());
    }

    @Test
    public void normal() {
        AbstractReferenceCounter counter = new AbstractReferenceCounter() {
        };
        assert counter.getRefCnt() == 0;
        assert counter.incrementRefCnt() == 1;
        assert counter.decrementRefCnt() == 0;

        Tuple<String, Integer> tuple = Tuple.of("s", 1);
        Tuple<String, Integer> tuple2 = Tuple.of("s", 1);
        assert tuple.equals(tuple2);

        assert eq(PersonGender.GIRL.description(), "女孩");
    }
}
