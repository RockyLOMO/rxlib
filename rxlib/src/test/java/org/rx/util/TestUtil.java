package org.rx.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.apache.commons.io.FilenameUtils;
import org.junit.jupiter.api.Test;
import org.rx.AbstractTester;
import org.rx.annotation.Mapping;
import org.rx.annotation.Metadata;
import org.rx.annotation.Subscribe;
import org.rx.bean.BiTuple;
import org.rx.bean.DateTime;
import org.rx.bean.FlagsEnum;
import org.rx.bean.ULID;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.test.GirlBean;
import org.rx.test.PersonBean;
import org.rx.test.PersonGender;
import org.rx.test.UserStruct;
import org.rx.third.guava.CaseFormat;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Month;
import java.util.Arrays;
import java.util.*;

import static org.rx.core.Extends.eq;
import static org.rx.core.Sys.*;

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
                return (int) (sourceValue.getTime() - DateTime.now().getTime());
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
        f.setBirth(new DateTime(2020, Month.FEBRUARY, 20, 0, 0, 0, TimeZone.getDefault()));
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

        String s = "{\"data\":{\"row\":{\"buyerTelephone\":\"18575686257\",\"deliveryTime\":\"2024-4-23 15:00-18:00\",\"coldChainStatus\":1,\"orderPaymentType\":3,\"orderOmsId\":\"102448352115074461\",\"deliveryStartTemperature\":\"0\",\"storePhone\":\"028-65239611\",\"deliveryEndTemperature\":\"0\",\"buyerFullName\":\"陈先生\",\"channelOrderId\":\"20009346551090415\",\"deliverTime\":\"2024-04-23 18:18:01\",\"deliveryBillItemId\":1062,\"deliveryManId\":92,\"serialNo\":\"TESTA001\",\"replySlip\":\"https://gjscrm-1256038144.cos.ap-beijing.myqcloud.com/mobile/050d022daed64bf2b0092515f354631a.jpg\",\"dispatchTime\":\"2024-04-23 18:17:52\",\"orderJson\":{\"orderBase\":{\"orderId\":20009346551090415,\"type\":10,\"userId\":-171386619041599999,\"businessId\":99999,\"storeId\":87446199999,\"orderStatus\":524352,\"orderStatusCur\":14,\"payAmount\":100,\"totalAmount\":100,\"couponAmount\":0,\"expressFee\":0,\"consigneeId\":0,\"consignee\":\"陈先生\",\"consigneePhone\":\"185****6257\",\"consigneeProvince\":\"\",\"consigneeCity\":\"\",\"consigneeArea\":\"\",\"consigneeAddress\":\"上海市四安***\",\"payChannel\":\"1\",\"status\":0,\"extend\":\"{\\\"advanceBookingType\\\":0,\\\"advancePayment\\\":0,\\\"appointmentFlag\\\":0,\\\"b2cSource\\\":0,\\\"coldchainind\\\":1,\\\"consigneeAddress\\\":\\\"\\\",\\\"coverPostAge\\\":false,\\\"dataSource\\\":0,\\\"deliveryMode\\\":3,\\\"ebaoCardPaidAmount\\\":0,\\\"epidemicFlag\\\":0,\\\"erpShiftId\\\":\\\"A\\\",\\\"extendTable\\\":1,\\\"fromCart\\\":0,\\\"fromOms\\\":0,\\\"fullAddress\\\":\\\"\\\",\\\"goodsOrderType\\\":1,\\\"hasAfterSale\\\":0,\\\"hmoCardPayStatus\\\":0,\\\"hmoPayFullNotifyStatus\\\":0,\\\"hmoPayFullStatus\\\":0,\\\"isAppointment\\\":0,\\\"isImagePrescription\\\":0,\\\"isNotify\\\":0,\\\"isRegisterEpidemic\\\":0,\\\"lossType\\\":1,\\\"newUserOnly\\\":false,\\\"notifyPos\\\":0,\\\"offSale\\\":false,\\\"orderWxPointsOverdue\\\":0,\\\"payJumpType\\\":1,\\\"payNo\\\":\\\"20020102404231818492473101990415\\\",\\\"pointsGiven\\\":false,\\\"posDeliveryEndTime\\\":\\\"18:00\\\",\\\"posDeliveryId\\\":\\\"92\\\",\\\"posDeliveryName\\\":\\\"连锁药师测试专用001\\\",\\\"posDeliveryStartTime\\\":\\\"15:00\\\",\\\"posDeliveryTime\\\":\\\"2024-4-23 15:00-18:00\\\",\\\"preCardPaidAmount\\\":0,\\\"preOrderId\\\":\\\"54af7987eef24ac78ee29270e1ff443c\\\",\\\"prescriptionFlag\\\":0,\\\"prescriptionProvid\\\":0,\\\"processId\\\":\\\"b410c62867a7443fa1f2016d2866462a\\\",\\\"processStartTime\\\":\\\"2024-04-23 17:56:18\\\",\\\"productName\\\":\\\"200\\\",\\\"productType\\\":200,\\\"promotionPayPoint\\\":0,\\\"realTimeAppointment\\\":0,\\\"relationRuleShow\\\":0,\\\"retureCouponFlag\\\":false,\\\"rxsalesind\\\":2,\\\"saleSaveCard\\\":false,\\\"scanType\\\":\\\"0\\\",\\\"selfPickupSiteGroupImg\\\":\\\"\\\",\\\"sendCouponFlag\\\":false,\\\"sendIntegralLimit\\\":0,\\\"stockLock\\\":false,\\\"storeExpressType\\\":0,\\\"tongChouPay\\\":true,\\\"totalFair\\\":0,\\\"transferGoodsFlag\\\":false,\\\"useMemberPrice\\\":1,\\\"useNewPromotion\\\":0,\\\"zdtUserCode\\\":\\\"16591565\\\",\\\"zdtUserId\\\":193893723199999,\\\"zdtUserName\\\":\\\"连锁药师测试专用001\\\"}\",\"version\":0,\"payPoints\":0,\"thirdPartyOrderNo\":\"610234722902\",\"promotionDiscounts\":0,\"memberPlusDiscounts\":0,\"itemType\":1,\"wipeMoney\":0,\"payType\":1,\"storeName\":\"01-scrm001测试\",\"totalOriginalAmount\":100,\"promotionLabel\":\"|S_0|\",\"cashier\":\"连锁药师测试专用001\",\"cashierId\":16591565,\"purchasePayAmount\":0,\"orderSign\":\"000000000\",\"offlineCouponAmount\":0,\"pointsDiscountAmount\":0,\"orderSource\":83,\"presentPoints\":0,\"payTime\":1713867930000,\"gmtCreate\":\"2024-04-23T09:56:31\",\"createdAt\":\"2024-04-23 17:56:31\"},\"orderDetails\":[{\"detailId\":20005657961190415,\"orderId\":20009346551090415,\"skuId\":1029942,\"skuName\":\"六神曲\",\"specificationsName\":\"无\",\"skuImage\":\"\",\"skuPrice\":100,\"couponAmount\":0,\"settlePrice\":100,\"points\":0,\"skuCount\":1,\"status\":0,\"extend\":\"{\\\"commonProperty\\\":\\\"{\\\\\\\"orderType\\\\\\\":\\\\\\\"10\\\\\\\"}\\\",\\\"healthInsurance\\\":false,\\\"isRx\\\":2,\\\"expectDays\\\":0,\\\"hasMappingCode\\\":false,\\\"transferGoodsFlag\\\":false,\\\"minPriceSaleState\\\":0,\\\"coldchainind\\\":2,\\\"isSpecial\\\":0,\\\"planWhole\\\":false,\\\"relationAdd\\\":0,\\\"pushLevel\\\":\\\"A5\\\",\\\"otcType\\\":2,\\\"packageGoods\\\":0,\\\"isBreakPrice\\\":0,\\\"isSimulatedGoods\\\":0}\",\"version\":0,\"itemId\":1029942,\"salesman\":\"连锁药师测试专用001\",\"salesmanId\":\"16591565\",\"itemCode\":\"1029942\",\"batchNo\":\"20180906\",\"promotionPrice\":100,\"displayPrice\":100,\"displayPriceType\":0,\"promotionDiscounts\":0,\"dismantled\":0,\"goodsUnit\":\"克\",\"goodsNo\":\"1029942\",\"promotionGift\":0,\"batchCode\":\"1932700001\",\"skuLabel\":\"\",\"pointsTimes\":100,\"businessId\":99999,\"storeId\":87446199999,\"orderStatusCur\":14,\"storeName\":\"01-scrm001测试\",\"cashier\":\"连锁药师测试专用001\",\"cashierId\":193893723199999,\"userCardNum\":\"\",\"username\":\"\",\"userPhone\":\"\",\"name\":\"六神曲\",\"expireDate\":\"2025-12-30 00:00:00\",\"producter\":\"四川弘升药业有限公司\",\"promotionInfo\":\"{}\",\"promotionShareTotal\":0,\"type\":10}]},\"coldNumber\":\"004\",\"buyerFllDetails\":\"陈先生 18575686257 上海市四安里8号\",\"buyerFullAddress\":\"上海市四安里8号\",\"payDetails\":[{\"id\":109,\"payId\":109,\"payNo\":\"20020102404231818492473101990415\",\"merchantOrderNo\":\"20009346551090415\",\"orderAmount\":100,\"orderPayAmount\":100,\"payAmount\":100,\"payTime\":1713867529000,\"refundAmount\":100,\"successAmount\":100,\"successTime\":1713867529000,\"payStatus\":1,\"channelType\":6,\"productType\":200,\"merchantId\":20,\"businessId\":99999,\"storeId\":87446199999,\"description\":\"\",\"settleType\":1,\"billingStatus\":0,\"payUserId\":-171386619041599999,\"requestIp\":\"127.0.0.2\",\"requestUserIp\":\"127.0.0.2\",\"status\":0,\"gmtCreate\":1713867529000,\"gmtUpdate\":1713867529000,\"extend\":\"{\\\"businessName\\\":\\\"00积分商城连锁\\\",\\\"cashierUserId\\\":\\\"16591565\\\",\\\"deviceInfoId\\\":0,\\\"mdmComId\\\":\\\"3000\\\",\\\"mdmStoreNo\\\":\\\"A00Z\\\",\\\"orderSource\\\":0,\\\"posOrderId\\\":\\\"20009346551090415\\\",\\\"riskFundAmount\\\":0,\\\"shareSubBusinessId\\\":0,\\\"storeName\\\":\\\"scrm001测试\\\"}\",\"version\":3,\"createdBy\":0,\"sapChannelType\":\"1000\",\"mchId\":\"123\"}],\"prescription\":{\"doctorHospital\":\"首都医科大学附属北京安贞医院\",\"doctorName\":\"陈志超\",\"medicines\":[{\"medicineCode\":\"1029942\",\"singleDosageUnit\":\"片\",\"unit\":\"盒\",\"medicineUsage\":\"口服\",\"medicineCommonName\":\"六神曲\",\"num\":1.0,\"specification\":\"无\",\"singleDosage\":\"1\",\"frequency\":\"1日1次\"}],\"presDoctorName\":\"朱一龙\",\"prescriptionDate\":\"2024-04-23 14:37:38\",\"doctorId\":229527513620001,\"prescriptionNumber\":\"610234722902\",\"diseaseDescription\":\"肠炎\",\"department\":\"内科\",\"patientInfoDTO\":{\"sex\":1,\"name\":\"苏欣荣\",\"age\":43.0}},\"deliveryName\":\"连锁药师测试专用001\",\"deliveryStatus\":50,\"coldBoxNumber\":\"A3333\"}},\"status\":0}";

        JSONObject sx = JSONObject.parseObject(s);
        System.out.println("asd:" + Sys.readJsonValue(sx, "data.row").toString());

        //compareVersion
        assert Strings.compareVersion("1.01", "1.001") == 0;
        assert Strings.compareVersion("1.0", "1.0.0") == 0;
        assert Strings.compareVersion("0.1", "1.1") == -1;

        String ce = "适用苹果ipad 10.2平板钢化膜9.7寸/air5全屏防爆膜2020/2021版高清贴膜10.9/11寸12.9寸护眼抗蓝紫光钢化膜";
        System.out.println(Strings.subStringByByteLen(ce, 78));

        String json = "{\n" +
                "  \"data\": {\n" +
                "    \"name\": \"张三\",\n" +
                "    \"age\": 10,\n" +
                "    \"mark\": [\n" +
                "      0,1,2,3,4,5,6,7,8,9,10,11,\n" +
                "      [\n" +
                "        2,\n" +
                "        3,\n" +
                "        {\n" +
                "          \"name\": \"李四\",\n" +
                "          \"mark\": [\n" +
                "            0,\n" +
                "            [\n" +
                "              1\n" +
                "            ]\n" +
                "          ]\n" +
                "        }\n" +
                "      ]\n" +
                "    ]\n" +
                "  },\n" +
                "  \"status\": 200\n" +
                "}";
        JSONObject jObj = toJsonObject(json);
        Object v;
        v = Sys.readJsonValue(jObj, "data", null, true);
        System.out.println(v);
        assert v instanceof JSONObject;

        v = Sys.readJsonValue(jObj, "data.mark", null, true);
        System.out.println(v);
        assert v instanceof JSONArray;

        v = Sys.readJsonValue(jObj, "status", null, true);
        System.out.println(v);
        assert eq(v, 200);

        v = Sys.readJsonValue(jObj, "data.name", null, true);
        System.out.println(v);
        assert eq(v, "张三");

        v = Sys.readJsonValue(jObj, "data.mark[1]", null, true);
        System.out.println(v);
        assert eq(v, 1);

        v = Sys.readJsonValue(jObj, "data.mark[11]", null, true);
        System.out.println(v);
        assert eq(v, 11);

        v = Sys.readJsonValue(jObj, "data.mark[12][1]", null, true);
        System.out.println(v);
        assert eq(v, 3);

        v = Sys.readJsonValue(jObj, "data.mark[12][2].name", null, true);
        System.out.println(v);
        assert eq(v, "李四");

        v = Sys.readJsonValue(jObj, "data.mark[12][2].mark[1][0]", null, true);
        System.out.println(v);
        assert eq(v, 1);

        JSONArray jArr = toJsonArray("[\n" +
                "  0,1,2,3,4,5,6,7,8,9,10,11,\n" +
                "  [\n" +
                "    2,\n" +
                "    3,\n" +
                "    {\n" +
                "      \"name\": \"李四\",\n" +
                "      \"mark\": [\n" +
                "        0,\n" +
                "        [\n" +
                "          1\n" +
                "        ]\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "]");
        v = Sys.readJsonValue(jArr, "[1]");
        System.out.println(v);
        assert eq(v, 1);

        v = Sys.readJsonValue(jArr, "[12][0]");
        System.out.println(v);
        assert eq(v, 2);

        v = Sys.readJsonValue(jArr, "[12][2].name");
        System.out.println(v);
        assert eq(v, "李四");

        v = Sys.readJsonValue(jArr, "[12][2].mark[1][0]");
        System.out.println(v);
        assert eq(v, 1);

        try {
            Sys.readJsonValue(jArr, "[12][2].mark2[1][0]");
        } catch (InvalidException e) {
            e.printStackTrace();
        }
        try {
            Sys.readJsonValue(jArr, "[12][3].mark2[1][0]");
        } catch (InvalidException e) {
            e.printStackTrace();
        }

        TestDemo demo = new TestDemo();
        demo.user = new UserDemo();
        demo.user.name = "张三";
        demo.user.age = 10;
        demo.group = org.rx.core.Arrays.toList(1, 0, 2, 4);

        assert eq(Sys.readJsonValue(demo, "user.name"), "张三");
        assert eq(Sys.readJsonValue(demo, "group[3]"), 4);

        System.out.println("的用法用量和注意事项，System回$}答{}要$求}简{洁}，36\n" +
                Strings.resolveVarExpression("${recordDrugName}的用法用量和注意事项，${x}回$}答{}要$求}简{洁}，${12}3${45}6",
                        Collections.singletonMap("x", "System")));
    }

    @Data
    public static class TestDemo {
        private UserDemo user;
        private List<Integer> group;
    }

    @Data
    public static class UserDemo {
        private String name;
        private int age;
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

        String s = "http://x.f-li.cn/asd";
        System.out.println(HttpUrl.get(s).host());
        System.out.println(HttpUrl.get(s).topPrivateDomain());
        System.out.println(URI.create(s).getHost());
    }
}
