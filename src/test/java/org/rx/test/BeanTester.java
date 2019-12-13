package org.rx.test;

import org.junit.jupiter.api.Test;
import org.rx.annotation.Mapping;
import org.rx.beans.FlagsEnum;
import org.rx.test.bean.PersonBean;
import org.rx.test.bean.PersonGender;
import org.rx.util.BeanMapConverter;
import org.rx.util.BeanMapFlag;
import org.rx.util.BeanMapper;
import org.rx.beans.DateTime;
import org.rx.beans.Tuple;
import org.rx.core.App;
import org.rx.beans.NEnum;
import org.rx.test.bean.TargetBean;
import org.rx.util.NullValueMappingStrategy;

import java.util.Date;

import static org.rx.core.Contract.toJsonString;

public class BeanTester {
    @Test
    public void testMapperCode() {
        System.out.println(BeanMapper.genCode(PersonBean.class));
    }

    //因为有default method，暂不支持abstract class
    interface PersonMapper {
        PersonMapper INSTANCE = BeanMapper.getInstance().define(PersonMapper.class);

        class DateToIntConvert implements BeanMapConverter<Date, Integer> {
            @Override
            public Integer convert(Date sourceValue, Class<Integer> targetType, String propertyName) {
                return (int) (sourceValue.getTime() - DateTime.BaseDate.getTime());
            }
        }

        //该interface下所有map方法的执行flags
        default FlagsEnum<BeanMapFlag> flags() {
            return BeanMapFlag.LogOnAllMapFail.flags();
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
        mapper.map(f, t, BeanMapFlag.LogOnAllMapFail.flags());  //target对象没有全部set或ignore则会记录WARN日志：Map PersonBean to TargetBean missed properties: kids, info, luckyNum
        System.out.println(toJsonString(f));
        System.out.println(toJsonString(t));
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
