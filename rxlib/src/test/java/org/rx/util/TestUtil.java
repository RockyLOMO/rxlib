package org.rx.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.AbstractTester;
import org.rx.annotation.Mapping;
import org.rx.annotation.Metadata;
import org.rx.annotation.Subscribe;
import org.rx.bean.*;
import org.rx.core.*;
import org.rx.bean.GirlBean;
import org.rx.bean.PersonBean;
import org.rx.bean.PersonGender;
import org.rx.test.UserStruct;
import org.rx.util.*;
import org.rx.third.guava.CaseFormat;

import java.lang.reflect.Method;
import java.util.*;
import java.util.Arrays;

import static org.rx.core.Sys.toJsonObject;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public class TestUtil extends AbstractTester {
    //region BeanMapper
    //因为有default method，暂不支持abstract class
    interface PersonMapper {
        PersonMapper INSTANCE = BeanMapper.DEFAULT.define(PersonMapper.class);

        //该interface下所有map方法的执行flags
        default FlagsEnum<BeanMapFlag> getFlags() {
            return BeanMapFlag.LOG_ON_MISS_MAPPING.flags();
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
        PersonBean source = PersonBean.YouFan;
        //定义用法
        GirlBean target = PersonMapper.INSTANCE.toTarget(source);
        System.out.println(toJsonString(source));
        System.out.println(toJsonString(target));
        assert source.getIndex2() == target.getIndex();
        assert source.getIndex2() == target.getLuckyNum();
        assert source.getCash().eq(target.getCash().doubleValue());

        target = new GirlBean();
        PersonMapper.INSTANCE.toTargetWith(source, target);
        System.out.println(toJsonString(source));
        System.out.println(toJsonString(target));
    }

    @Test
    public void normalMapBean() {
        PersonBean f = new PersonBean();
        f.setIndex(2);
        f.setName(str_name_wyf);
        f.setAge(6);
        f.setBirth(new DateTime(2020, 2, 20, 0, 0, 0));
        f.setGender(PersonGender.BOY);
        f.setCashCent(200L);
        GirlBean t = new GirlBean();
        t.setKids(10L);

        //普通用法，属性名一致
        BeanMapper mapper = BeanMapper.DEFAULT;
//        mapper.map(f, t, BeanMapFlag.ThrowOnAllMapFail.flags());  //target对象没有全部set或ignore则会抛出异常
        mapper.map(f, t, BeanMapFlag.LOG_ON_MISS_MAPPING.flags());  //target对象没有全部set或ignore则会记录WARN日志：Map PersonBean to TargetBean missed properties: kids, info, luckyNum
        System.out.println(toJsonString(f));
        System.out.println(toJsonString(t));


        String input = "VerifyCouponDTO(businessId=512101, storeId=2752182412101, storeName=null, userId=2598980608312101, couponId=52413912101, couponCode=88130205610006083, showCouponCode=88130205610006083, channel=1, businessType=null, type=1, verifyBy=2610449899512101, verifyName=null, verifyDate=2022-07-30 15:35:34, orderType=null, orderId=220730039949230215, bizId=203547#0, extendMap={sendOrderId=65187421241005834})";
        int c = 0;
        for (String str : Strings.split(input, "\n")) {
            if (!str.startsWith("VerifyCouponDTO")) {
                continue;
            }
            System.out.println(++c);
            System.out.println(toJsonString(BeanMapper.convertFromObjectString(str, true)));
            System.out.println();
        }
        System.out.println(c);
    }
    //endregion

    @Test
    @SneakyThrows
    public void validate() {
        GirlBean girl = new GirlBean();
        try {
            Validator.validateBean(girl);
        } catch (ValidateException e) {
            e.printStackTrace();
            assert Linq.from("id", "name").contains(e.getPropertyPath());
        }
        girl.setId(ULID.randomULID());
        girl.setName(str_name_wyf);
        girl.setSister(new GirlBean());
        try {
            Validator.validateBean(girl);
        } catch (ValidateException e) {
            e.printStackTrace();
            assert Linq.from("sister.id", "sister.name").contains(e.getPropertyPath());
        }

        Method m = GirlBean.class.getMethod("renew", List.class);
        try {
            Validator.validateMethod(m, girl, new Object[]{null}, null);
        } catch (ValidateException e) {
            e.printStackTrace();
            assert Linq.from("renew.arg0").contains(e.getPropertyPath());
        }
        try {
            Validator.validateMethod(m, girl, new Object[]{Collections.singletonList(new GirlBean())}, null);
        } catch (ValidateException e) {
            e.printStackTrace();
            assert Linq.from("renew.arg0[0].id", "renew.arg0[0].name").contains(e.getPropertyPath());
        }
        try {
            Validator.validateMethod(m, girl, new Object[]{Collections.emptyList()}, () -> null);
        } catch (ValidateException e) {
            e.printStackTrace();
            assert Linq.from("renew.<return value>").contains(e.getPropertyPath());
        }
        Validator.validateMethod(m, girl, new Object[]{Collections.emptyList()}, () -> 0);
    }

    //region changeTracker
    @Test
    public void objectChangeTracker() {
        ObjectChangeTracker tracker = ObjectChangeTracker.DEFAULT;
        Map<String, Object> valueMap1, valueMap2;
        Map<String, ObjectChangeTracker.ChangedValue> changedMap;

        List<PersonBean> arr1 = Collections.singletonList(PersonBean.YouFan);
        List<PersonBean> arr2 = Arrays.asList(PersonBean.YouFan, PersonBean.LeZhi);
        Map<String, PersonBean> map1 = Collections.singletonMap("one", PersonBean.LeZhi);
        Map<String, PersonBean> map2 = new HashMap<>();
        map2.put("one", PersonBean.LeZhi);
        map2.put("two", PersonBean.YouFan);

        valueMap1 = ObjectChangeTracker.getSnapshotMap(BiTuple.of(PersonBean.YouFan, arr1, map1), false);
        System.out.println(valueMap1);
        System.out.println(toJsonString(valueMap1));
        valueMap2 = ObjectChangeTracker.getSnapshotMap(BiTuple.of(PersonBean.LeZhi, arr2, map2), false);
        System.out.println(toJsonString(valueMap2));
        changedMap = ObjectChangeTracker.compareSnapshotMap(valueMap1, valueMap2);
        log.info("changedMap\n{}", toJsonString(changedMap));

//        GirlBean girl = GirlBean.YouFan;
//
//        valueMap1 = ObjectChangeTracker.getSnapshotMap(girl, false);
//        girl.setIndex(1);
//        girl.setObj(1);
//        girl.setFlags(new int[]{0, 1});
//        girl.setSister(new GirlBean());
//        valueMap2 = ObjectChangeTracker.getSnapshotMap(girl, false);
//        changedMap = ObjectChangeTracker.compareSnapshotMap(valueMap1, valueMap2);
//        log.info("changedMap\n{}", toJson(changedMap));
//
//        valueMap1 = valueMap2;
//        girl.setIndex(64);
//        girl.setObj("a");
//        girl.setFlags(new int[]{0, 2, 3});
//        girl.getSister().setAge(5);
//        valueMap2 = ObjectChangeTracker.getSnapshotMap(girl, false);
//        changedMap = ObjectChangeTracker.compareSnapshotMap(valueMap1, valueMap2);
//        log.info("changedMap\n{}", toJson(changedMap));
//
//        ObjectChangeTracker tracker = new ObjectChangeTracker(2000);
////        tracker.watch(RxConfig.INSTANCE,true);
//        tracker.watch(girl).register(this);
//        Tasks.setTimeout(() -> {
//            girl.setIndex(128);
//            girl.setObj(new ArrayList<>());
//            girl.setFlags(new int[]{0});
//            girl.getSister().setAge(3);
//        }, 5000);

        TopicBean b1 = new TopicBean();
        b1.setName("张三");
        b1.setGender(1);
        b1.setAge(10);
        b1.tb = new TopicBean();
        tracker.register(this).watch(b1);
        Tasks.setTimeout(() -> {
            b1.setName("李四");
//            b1.tb = new TopicBean();
            b1.tb.setName("老王");
//            log.info("", b1);
        }, 1000);
        _wait();
    }

    String toJson(Object source) {
        return JSON.toJSONString(source, JSONWriter.Feature.WriteNulls);
    }

    @Metadata(topicClass = TopicBean.class)
    @Data
    public static class TopicBean {
        String name;
        int gender;
        int age;

        TopicBean tb;
    }

    @Subscribe
    void onChange(ObjectChangedEvent e) {
        log.info("change {} ->\n{}", e.getSource(), toJson(e.getChangedMap()));
//        sleep(10000);
//        _notify();
    }

    @Subscribe(topicClass = TopicBean.class)
    void onChangeWithTopic(ObjectChangedEvent e) {
        log.info("changeWithTopic {} ->\n{}", e.getSource(), toJson(e.getChangedMap()));
        String newName = e.readValue("tb.name");
        System.out.println(newName);
    }
    //endregion

    @Test
    public void other() {
//        //snowflake
//        Set<Long> set = new HashSet<>();
//        int len = 1 << 12;
//        System.out.println(len);
//        Snowflake snowflake = Snowflake.DEFAULT;
//        for (int i = 0; i < len; i++) {
//            Tasks.run(() -> {
//                assert set.add(snowflake.nextId());
//            });
//        }

        //compareVersion
        assert Strings.compareVersion("1.01", "1.001") == 0;
        assert Strings.compareVersion("1.0", "1.0.0") == 0;
        assert Strings.compareVersion("0.1", "1.1") == -1;

        String ce = "适用苹果ipad 10.2平板钢化膜9.7寸/air5全屏防爆膜2020/2021版高清贴膜10.9/11寸12.9寸护眼抗蓝紫光钢化膜";
        System.out.println(Strings.subStringByByteLen(ce, 78));

        String json = "{\"ctp\":\"pages/gold/item/pages/detail/index\",\"par\":\"\",\"ref\":\"pages/gold/item/pages/detail/index\",\"rpr\":\"\",\"seq\":2,\"vts\":110,\"pin\":\"jd_759f867040f60\",\"fst\":\"1577630901829\",\"pst\":\"1677280413\",\"vct\":\"1677479554\",\"jsver\":\"TR1.0.0\",\"net\":\"wifi\",\"lat\":\"\",\"lon\":\"\",\"speed\":\"\",\"accuracy\":\"\",\"pixelRatio\":\"1\",\"jdv\":\"1|weixin|t_335139774_xcx_1036_appfxxx|xcx|-|1677479554238\",\"customerInfo\":\"\",\"unpl\":\"\",\"scene\":1036,\"sdkver\":\"2.19.2\",\"ext\":{},\"eid\":\"W_jdgwxcx_productdetail_consultationiconexpo\",\"eparam\":\"{\\\"mainskuid\\\":100006612085}\",\"elevel\":\"\",\"page_id\":\"W_jdgwxcx_productdetail\",\"pname\":\"pages/gold/item/pages/detail/index\",\"pparam\":\"\",\"tar\":\"\",\"x\":0,\"y\":0,\"typ\":\"ep\"}";
        Object v = Sys.readJsonValue(toJsonObject(json), "eparam.mainskuid", Sys::toJsonObject, true);
        System.out.println(v);
    }

    @Test
    public void third() {
        Map<Object, Integer> identityMap = new WeakIdentityMap<>();
        UserStruct k = new UserStruct();
        identityMap.put(k, 1);
        k.age = 2;
        identityMap.put(k, 2);
        assert identityMap.size() == 1;
        System.out.println(toJsonString(identityMap.entrySet()));

        System.out.println(FilenameUtils.getFullPath("a.txt"));
        System.out.println(FilenameUtils.getFullPath("c:\\a\\b.txt"));
        System.out.println(FilenameUtils.getFullPath("/a/b.txt"));

        assert "ROW_ID".equals(CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, "rowId"));
        assert "ROW_ID".equals(CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, "RowId"));
    }
}
