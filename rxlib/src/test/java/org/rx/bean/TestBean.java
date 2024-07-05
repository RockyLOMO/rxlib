package org.rx.bean;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.AbstractTester;
import org.rx.core.Arrays;
import org.rx.core.StringBuilder;
import org.rx.core.Tasks;
import org.rx.io.Bytes;
import org.rx.test.PersonBean;
import org.rx.test.PersonGender;

import java.time.DayOfWeek;
import java.time.Month;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;

import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.sleep;
import static org.rx.core.Sys.fromJson;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public class TestBean extends AbstractTester {
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
    public void ulid() {
        String jstr = toJsonString(ULID.randomULID());
        ULID id = fromJson(jstr, ULID.class);
        assert jstr.substring(1, jstr.length() - 1).equals(id.toString());

        ULID id1 = ULID.newULID(str_name_wyf, System.currentTimeMillis());
        System.out.println(id1);
        ULID valueOf = ULID.valueOf(id1.toString());
        System.out.println(valueOf);
        assert id1.equals(valueOf);
        valueOf = ULID.valueOf(id1.toBase64String());
        assert id1.equals(valueOf);

        Set<ULID> set = new HashSet<>();
        int len = 100;  //1530ms
        invoke("ulid", i -> {
            ULID x = ULID.randomULID();
            System.out.println(x);
            set.add(x);
            assert ULID.valueOf(x.toString()).equals(x);
        }, len);
        assert set.size() == len;

        for (int i = 0; i < 20; i++) {
            long ts = System.currentTimeMillis();
            assert ULID.newULID(Bytes.getBytes(i), ts).equals(ULID.newULID(Bytes.getBytes(i), ts));
        }

        for (int i = 0; i < 20; i++) {
            long ts = System.currentTimeMillis();
            assert ts == ULID.newULID(Bytes.getBytes(i), ts).getTimestamp();
        }
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
        DateTime d = new DateTime(2010, Month.AUGUST, 24, 11, 12, 13, TimeZone.getDefault());
        DateTime d3 = new DateTime(2010, Month.AUGUST, 23, 11, 12, 13, TimeZone.getDefault());

        assert now.getTime() == utc.getTime();
        assert d.getYear() == 2010;
        assert d.getMonth() == 8;
        assert d.getDay() == 24;

        DateTime d2 = d.addYears(1);
        assert d.getYear() == 2010;
        assert d2.getYear() == 2011;
        assert d.subtract(d3).getTotalHours() == 24;

        long ts = d.getTime();
        System.out.println(ts);
        System.out.println(d);
        String sts = String.valueOf(ts);
        System.out.println(d.toString(DateTime.ISO_DATE_TIME_FORMAT));
        assert d.toString(DateTime.ISO_DATE_TIME_FORMAT).equals("2010-08-24T11:12:13." + sts.substring(sts.length() - 3) + "+0800");
        assert d.getTimePart().toDateTimeString().equals("1970-01-01 11:12:13");
        assert d.setTimePart("14:30:01").toDateTimeString().equals("2010-08-24 14:30:01");
        assert d.setTimePart(14, 30, 1).toDateTimeString().equals("2010-08-24 14:30:01");
        assert d.getDatePart().toDateTimeString().equals("2010-08-24 00:00:00");
        assert d.setDatePart("2022-02-02").toDateTimeString().equals("2022-02-02 11:12:13");
        assert d.setDatePart(2022, Month.FEBRUARY, 2).toDateTimeString().equals("2022-02-02 11:12:13");

        DateTime nd = DateTime.valueOf("2024-04-10 00:20:00");
        assert nd.getDayOfWeek() == DayOfWeek.WEDNESDAY;
        log.info("{} nextDayOfWeek {}", nd, nd.nextDayOfWeek());
        log.info("{} lastDayOfMonth {}", nd, nd.lastDayOfMonth());
//        log.info("{} isToday {}", nd, nd.isToday());
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
