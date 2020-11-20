package org.rx.test;

import org.junit.jupiter.api.Test;
import org.rx.annotation.Mapping;
import org.rx.bean.*;
import org.rx.test.bean.PersonBean;
import org.rx.test.bean.PersonGender;
import org.rx.test.common.TestUtil;
import org.rx.util.BeanMapConverter;
import org.rx.util.BeanMapFlag;
import org.rx.util.BeanMapper;
import org.rx.test.bean.TargetBean;
import org.rx.util.BeanMapNullValueStrategy;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import static org.rx.core.Contract.eq;
import static org.rx.core.Contract.toJsonString;

public class BeanTester extends TestUtil {
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
        @Mapping(target = "kids", defaultValue = "1024", nullValueStrategy = BeanMapNullValueStrategy.SetToDefault)
        @Mapping(target = "birth", converter = DateToIntConvert.class)
        TargetBean toTarget(PersonBean source);

        @Mapping(target = "gender", ignore = true)
        @Mapping(source = "name", target = "info", trim = true, format = "a%sb")
        @Mapping(target = "kids", nullValueStrategy = BeanMapNullValueStrategy.Ignore)
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
    public void randomList() {
        RandomList<String> list = new RandomList<>();
        list.add("a", 4);
        list.add("b", 3);
        list.add("c", 2);
        list.add("d", 1);
        for (String s : list) {
            System.out.println(s);
        }
        for (int i = 0; i < 20; i++) {
            System.out.println(list.next());
        }
        for (int i = 0; i < 10000; i++) {
            String next = list.next();
            System.out.println(next);
            list.remove(next);
            list.add(next);
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
        invoke("suid", () -> {
            for (int i = 0; i < len; i++) {
                SUID suid1 = SUID.randomSUID();
                System.out.println(suid1.toString());
                set.add(suid1);

                assert SUID.valueOf(suid1.toString()).equals(suid1);
            }
        });
        assert set.size() == len;
    }

    @Test
    public void permille() {
        Permille permille = Permille.valueOf("50%");
        Permille permille1 = Permille.valueOf("500‰");
        Permille permille2 = Permille.valueOf(0.5);
        assert permille.equals(permille1) && permille1.equals(permille2);
        System.out.println(permille1.toString());
        System.out.println(permille1.toPermilleString());
        System.out.println(permille1.toPermilleInt());
        System.out.println(permille1.toPercentString());
        System.out.println(permille1.toPercentInt());
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
