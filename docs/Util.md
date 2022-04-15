## RandomList 随机权重List

随机权重算法，二分查找优化，同一对象尽量分发相同元素。

```java
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
```



## BeanMapper 基于cglib bytecode实现

实现思路参考mapstruct。[cglib性能参考](https://yq.aliyun.com/articles/14958)。

```java
//因为有default method，暂不支持abstract class
interface PersonMapper {
    PersonMapper INSTANCE = BeanMapper.INSTANCE.define(PersonMapper.class);

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
    f.setName(TConfig.NAME_WYF);
    f.setAge(6);
    f.setBirth(new DateTime(2020, 2, 20));
    f.setGender(PersonGender.BOY);
    f.setMoneyCent(200L);
    GirlBean t = new GirlBean();
    t.setKids(10L);

    //普通用法，属性名一致
    BeanMapper mapper = BeanMapper.INSTANCE;
      mapper.map(f, t, BeanMapFlag.ThrowOnAllMapFail.flags());  //target对象没有全部set或ignore则会抛出异常
    mapper.map(f, t, BeanMapFlag.LOG_ON_MISS_MAPPING.flags());  //target对象没有全部set或ignore则会记录WARN日志：Map PersonBean to TargetBean missed properties: kids, info, luckyNum
    System.out.println(toJsonString(f));
    System.out.println(toJsonString(t));
}
```