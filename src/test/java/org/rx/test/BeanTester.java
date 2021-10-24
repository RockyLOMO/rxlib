package org.rx.test;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.junit.jupiter.api.Test;
import org.rx.annotation.Mapping;
import org.rx.bean.*;
import org.rx.core.App;
import org.rx.core.StringBuilder;
import org.rx.core.Tasks;
import org.rx.test.bean.PersonBean;
import org.rx.test.bean.PersonGender;
import org.rx.test.common.TestUtil;
import org.rx.util.BeanMapConverter;
import org.rx.util.BeanMapFlag;
import org.rx.util.BeanMapper;
import org.rx.test.bean.GirlBean;
import org.rx.util.BeanMapNullValueStrategy;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.CountDownLatch;

import static org.rx.core.App.*;

@Slf4j
public class BeanTester extends TestUtil {
    //因为有default method，暂不支持abstract class
    interface PersonMapper {
        PersonMapper INSTANCE = BeanMapper.INSTANCE.define(PersonMapper.class);

        //该interface下所有map方法的执行flags
        default FlagsEnum<BeanMapFlag> getFlags() {
            return BeanMapFlag.LogOnNotAllMapped.flags();
        }

        class DateToIntConvert implements BeanMapConverter<Date, Integer> {
            @Override
            public Integer convert(Date sourceValue, Class<Integer> targetType, String propertyName) {
                return (int) (sourceValue.getTime() - DateTime.MIN.getTime());
            }
        }

        @Mapping(target = "index", source = "index2")
        @Mapping(source = "name", target = "info", trim = true, format = "a%sb")
        @Mapping(target = "gender", ignore = true)
        @Mapping(target = "birth", converter = DateToIntConvert.class)
        @Mapping(target = "kids", defaultValue = "1024", nullValueStrategy = BeanMapNullValueStrategy.SetToDefault)
        @Mapping(target = "luckyNum", source = "index2")
        GirlBean toTarget(PersonBean source);

        @Mapping(target = "gender", ignore = true)
        @Mapping(source = "name", target = "info", trim = true, format = "a%sb")
        @Mapping(target = "kids", nullValueStrategy = BeanMapNullValueStrategy.Ignore)
        @Mapping(target = "birth", converter = DateToIntConvert.class)
        default GirlBean toTargetWith(PersonBean source, GirlBean target) {
            target.setKids(10L);//自定义默认值，先执行默认方法再copy properties
            return target;
        }
    }

    @Test
    public void defineMapBean() {
        PersonBean source = PersonBean.boy;
        //定义用法
        GirlBean target = PersonMapper.INSTANCE.toTarget(source);
        System.out.println(toJsonString(source));
        System.out.println(toJsonString(target));
        assert source.getIndex2() == target.getIndex();
        assert source.getIndex2() == target.getLuckyNum();
        assert source.getMoney().eq(target.getMoney().doubleValue());

        target = new GirlBean();
        PersonMapper.INSTANCE.toTargetWith(source, target);
        System.out.println(toJsonString(source));
        System.out.println(toJsonString(target));
    }

    @Test
    public void normalMapBean() {
        PersonBean f = new PersonBean();
        f.setIndex(2);
        f.setName("王湵范");
        f.setAge(6);
        f.setBirth(new DateTime(2020, 2, 20));
        f.setGender(PersonGender.Boy);
        f.setMoneyCent(200L);
        GirlBean t = new GirlBean();
        t.setKids(10L);

        //普通用法，属性名一致
        BeanMapper mapper = BeanMapper.INSTANCE;
//        mapper.map(f, t, BeanMapFlag.ThrowOnAllMapFail.flags());  //target对象没有全部set或ignore则会抛出异常
        mapper.map(f, t, BeanMapFlag.LogOnNotAllMapped.flags());  //target对象没有全部set或ignore则会记录WARN日志：Map PersonBean to TargetBean missed properties: kids, info, luckyNum
        System.out.println(toJsonString(f));
        System.out.println(toJsonString(t));
    }

    @SneakyThrows
    @Test
    public void randomList() {
        RandomList<String> list = new RandomList<>();
        list.add("a", 4);
        list.add("b", 3);
        list.add("c", 2);
        list.add("d", 1);
        list.add("e", 0);
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
                    str.append(list.next() + " ");
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
        Object steeringObj = 1;
        int ttl = 2;
        String next = list.next(steeringObj, ttl);
        assert next.equals(list.next(steeringObj, ttl));
        log.info("steering {} -> {}", steeringObj, next);
        sleep(5000);
        String after = list.next(steeringObj, ttl);
        log.info("steering {} -> {} | {}", steeringObj, next, after);
        assert after.equals(list.next(steeringObj, ttl));
    }

    @Test
    public void snowFlake() {
        SnowFlake snowFlake = new SnowFlake(2, 3);
        for (int i = 0; i < (1 << 12); i++) {
            System.out.println(snowFlake.nextId());
        }
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

        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            map.put(i, System.nanoTime());
            System.out.println(App.combId(map.get(i), "" + i));
        }
        for (int i = 0; i < 10; i++) {
            assert App.combId(map.get(i), "" + i).equals(App.combId(new Timestamp(map.get(i)), "" + i));
        }

        String d = "wyf520";
        SUID suid = SUID.compute(d);
        System.out.println(suid.toString());

        SUID valueOf = SUID.valueOf(suid.toString());
        System.out.println(valueOf.toString());

        assert suid.equals(valueOf);

        Set<SUID> set = new HashSet<>();
        int len = 100;  //1530ms
        invoke("suid", i -> {
            SUID suid1 = SUID.randomSUID();
            System.out.println(suid1.toString());
            set.add(suid1);

            assert SUID.valueOf(suid1.toString()).equals(suid1);
        }, len);
        assert set.size() == len;
    }

    @Test
    public void decimal() {
        Object x = true;
        assert x instanceof Boolean && BooleanUtils.isTrue((Boolean) x);

        Decimal permille = Decimal.valueOf("50%");
        Decimal permille1 = Decimal.valueOf("500‰");
        Decimal permille2 = Decimal.valueOf(0.5);
        assert permille.equals(permille1) && permille1.equals(permille2);
        System.out.println(permille1.toString());
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

        String j = toJsonString(PersonBean.girl);
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

        System.out.println(now.toString());
        System.out.println(utc.toString());
        System.out.println(d.toDateTimeString());

        DateTime.valueOf("x");
    }

    @Test
    public void nenum() {
        assert eq(PersonGender.Girl.description(), "女孩");
    }

    @Test
    public void tuple() {
        Tuple<String, Integer> tuple = Tuple.of("s", 1);
        tuple.setRight(2);
        Tuple<String, Integer> tuple2 = Tuple.of("s", 1);
        Tuple<String, Integer> tuple3 = Tuple.of("s", 0);

        assert tuple.right == 1;
        assert tuple.equals(tuple2);
        assert !tuple.equals(tuple3);
    }
}
