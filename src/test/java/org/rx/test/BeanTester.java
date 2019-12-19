package org.rx.test;

import com.google.common.base.Stopwatch;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.annotation.Mapping;
import org.rx.beans.*;
import org.rx.test.bean.PersonBean;
import org.rx.test.bean.PersonGender;
import org.rx.util.BeanMapConverter;
import org.rx.util.BeanMapFlag;
import org.rx.util.BeanMapper;
import org.rx.core.App;
import org.rx.test.bean.TargetBean;
import org.rx.util.NullValueMappingStrategy;
import org.rx.util.function.Action;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Contract.toJsonString;

public class BeanTester {
    //因为有default method，暂不支持abstract class
    interface PersonMapper {
        PersonMapper INSTANCE = BeanMapper.getInstance().define(PersonMapper.class);

        //该interface下所有map方法的执行flags
        default FlagsEnum<BeanMapFlag> getFlags() {
            return BeanMapFlag.LogOnNotAllMapped.flags();
        }

        class DateToIntConvert implements BeanMapConverter<Date, Integer> {
            @Override
            public Integer convert(Date sourceValue, Class<Integer> targetType, String propertyName) {
                return (int) (sourceValue.getTime() - DateTime.BaseDate.getTime());
            }
        }

        @Mapping(target = "gender", ignore = true)
        @Mapping(source = "name", target = "info", trim = true, format = "a%sb")
        @Mapping(target = "kids", defaultValue = "1024", nullValueStrategy = NullValueMappingStrategy.SetToDefault)
        @Mapping(target = "birth", converter = DateToIntConvert.class)
        TargetBean toTarget(PersonBean source);

        @Mapping(target = "gender", ignore = true)
        @Mapping(source = "name", target = "info", trim = true, format = "a%sb")
        @Mapping(target = "kids", nullValueStrategy = NullValueMappingStrategy.Ignore)
        @Mapping(target = "birth", converter = DateToIntConvert.class)
        default TargetBean toTargetWith(PersonBean source, TargetBean target) {
            target.setKids(10L);//自定义默认值，先执行默认方法再copy properties
            return target;
        }
    }

    @Test
    public void defineMapBean() {
        PersonBean f = new PersonBean();
        f.setIndex(2);
        f.setName("王湵范");
        f.setAge(6);
        f.setBirth(new DateTime(2020, 2, 20));
        f.setGender(PersonGender.Boy);
        f.setMoney(200L);

        //定义用法
        TargetBean result = PersonMapper.INSTANCE.toTarget(f);
        System.out.println(toJsonString(f));
        System.out.println(toJsonString(result));

        result = new TargetBean();
        PersonMapper.INSTANCE.toTargetWith(f, result);
        System.out.println(toJsonString(f));
        System.out.println(toJsonString(result));
    }

    @Test
    public void normalMapBean() {
        PersonBean f = new PersonBean();
        f.setIndex(2);
        f.setName("王湵范");
        f.setAge(6);
        f.setBirth(new DateTime(2020, 2, 20));
        f.setGender(PersonGender.Boy);
        f.setMoney(200L);
        TargetBean t = new TargetBean();
        t.setKids(10L);

        //普通用法，属性名一致
        BeanMapper mapper = BeanMapper.getInstance();
//        mapper.map(f, t, BeanMapFlag.ThrowOnAllMapFail.flags());  //target对象没有全部set或ignore则会抛出异常
        mapper.map(f, t, BeanMapFlag.LogOnNotAllMapped.flags());  //target对象没有全部set或ignore则会记录WARN日志：Map PersonBean to TargetBean missed properties: kids, info, luckyNum
        System.out.println(toJsonString(f));
        System.out.println(toJsonString(t));
    }

    @Test
    public void rndList() {
        RandomList<String> randomList = new RandomList<>();
        randomList.add("a", 5);
        randomList.add("b", 5);
        randomList.add("c", 5);
        randomList.add("d", 5);
        for (int i = 0; i < 100000; i++) {
            String next = randomList.next();
            System.out.println(next);
            randomList.remove(next);
            randomList.add(next);
        }
    }

    @Test
    public void suid() {
        String d = "wyf520";
        SUID suid = SUID.compute(d);
        System.out.println(suid.toString());

        SUID valueOf = SUID.valueOf(suid.toString());
        System.out.println(valueOf.toString());

        assert suid.equals(valueOf);

        Set<SUID> set = new HashSet<>();
        int len = 100000;  //1530ms
        invoke(() -> {
            for (int i = 0; i < len; i++) {
                SUID suid1 = SUID.randomSUID();
                System.out.println(suid1.toString());
                set.add(suid1);

                assert SUID.valueOf(suid1.toString()).equals(suid1);
            }
        });
        assert set.size() == len;
    }

    @SneakyThrows
    private void invoke(Action action) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        action.invoke();
        System.out.println(stopwatch.elapsed(TimeUnit.MILLISECONDS) + "ms");
    }

    @Test
    public void testConvert() {
        App.registerConverter(Integer.class, PersonGender.class, (fromValue, toType) -> NEnum.valueOf(toType, fromValue));
        App.registerConverter(PersonGender.class, Integer.class, (p1, p2) -> p1.getValue());

        int val = App.changeType(PersonGender.Boy, Integer.class);
        assert val == 1;

        PersonGender testEnum = App.changeType(1, PersonGender.class);
        assert testEnum == PersonGender.Boy;
        int integer = App.changeType("1", Integer.class);
        assert integer == 1;
    }

    @Test
    public void testDate() {
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
    public void testEnum() {
        System.out.println(PersonGender.Girl.toDescription());
    }

    @Test
    public void testTuple() {
        Tuple<String, Integer> tuple = Tuple.of("s", 1);
        tuple.setRight(2);
        Tuple<String, Integer> tuple2 = Tuple.of("s", 1);
        Tuple<String, Integer> tuple3 = Tuple.of("s", 0);

        assert tuple.right == 1;
        assert tuple.equals(tuple2);
        assert !tuple.equals(tuple3);
    }
}
