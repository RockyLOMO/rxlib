package org.rx.test;

import lombok.Getter;
import org.junit.jupiter.api.Test;
import org.rx.annotation.Description;
import org.rx.util.BeanMapper;
import org.rx.beans.DateTime;
import org.rx.beans.Tuple;
import org.rx.core.App;
import org.rx.test.bean.ErrorBean;
import org.rx.beans.NEnum;
import org.rx.test.bean.SourceBean;
import org.rx.test.bean.TargetBean;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;

public class BeanTester {
    @Test
    public void testMapperCode() {
        System.out.println(BeanMapper.genCode(SourceBean.class));
    }

    @Test
    public void testMapper() {

        BeanMapper mapper = new BeanMapper();
        mapper.setConfig(SourceBean.class, TargetBean.class, targetProperty -> {
            switch (targetProperty) {
                case "info":
                    return "name";
                case "luckyNum":
                    return BeanMapper.IgnoreProperty;
            }
//            return null;
            return targetProperty;
        }, (targetProperty, sourceTuple) -> {
            switch (targetProperty) {
                case "age":
                    return sourceTuple.left.toString();
                case "money":
                    return new BigDecimal((Long) sourceTuple.left);
            }
            return sourceTuple.left;
        });

        SourceBean f = new SourceBean();
        f.setName("HW ");
        f.setAge(100);
        f.setMoney(200L);
        TargetBean t = new TargetBean();
        t.setKids(10L);
        mapper.map(f, t, BeanMapper.Flags.TrimString.add(BeanMapper.Flags.SkipNull));
        System.out.println(t);
        assert t.getName().equals(f.getName().trim());
        assert t.getInfo().equals(f.getName().trim());
        assert t.getLuckyNum() == 0;
    }

    @Test
    public void testConvert() {
        System.out.println(TestEnum.class.isAssignableFrom(NEnum.class));
        System.out.println(NEnum.class.isAssignableFrom(TestEnum.class));
        System.out.println(TestEnum.class.isAssignableFrom(TestEnum.class));
        App.registerConverter(Integer.class, TestEnum.class, (fromValue, toType) -> NEnum.valueOf(toType, fromValue));
        App.registerConverter(TestEnum.class, Integer.class, (p1, p2) -> p1.getValue());

        int val = App.changeType(TestEnum.One, Integer.class);
        assert val == 1;

        TestEnum testEnum = App.changeType(1, TestEnum.class);
        assert testEnum == TestEnum.One;
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
    public void testTuple() {
        Tuple<String, Integer> tuple = Tuple.of("s", 1);
        tuple.setRight(2);
        Tuple<String, Integer> tuple2 = Tuple.of("s", 1);
        Tuple<String, Integer> tuple3 = Tuple.of("s", 0);

        assert tuple.right == 1;
        assert tuple.equals(tuple2);
        assert !tuple.equals(tuple3);
    }

    @Test
    public void testConstructor() throws Exception {
        Constructor constructor = ErrorBean.class.getConstructor(new Class[0]);
        System.out.println(constructor != null);
    }

    public enum TestEnum implements NEnum<TestEnum> {
        @Description("1")
        One(1),
        @Description("2")
        Two(2);

        @Getter
        private int value;

        TestEnum(int val) {
            value = val;
        }
    }

    @Test
    public void testEnum() {
        System.out.println(TestEnum.Two.toDescription());
    }
}
