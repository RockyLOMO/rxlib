## Linq parallel lambda query

```java
@Test
public void runLinq() {
    Collection<PersonBean> personSet = new HashSet<>();
    personSet.add(PersonBean.LeZhi);
    for (int i = 0; i < 5; i++) {
        PersonBean p = new PersonBean();
        p.index = i % 2 == 0 ? 2 : i;
        p.index2 = i % 2 == 0 ? 3 : 4;
        p.name = Strings.randomValue(5);
        p.age = ThreadLocalRandom.current().nextInt(100);
        personSet.add(p);
    }

    showResult("leftJoin", Linq.from(new PersonBean(27, 27, "jack", PersonGender.BOY, 6, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
            new PersonBean(28, 28, "tom", PersonGender.BOY, 6, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
            new PersonBean(29, 29, "lily", PersonGender.GIRL, 8, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
            new PersonBean(30, 30, "cookie", PersonGender.BOY, 6, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array)).leftJoin(
            Arrays.toList(new PersonBean(27, 27, "cookie", PersonGender.BOY, 5, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
                    new PersonBean(28, 28, "tom", PersonGender.BOY, 10, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
                    new PersonBean(29, 29, "jack", PersonGender.BOY, 1, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
                    new PersonBean(30, 30, "session", PersonGender.BOY, 25, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
                    new PersonBean(31, 31, "trump", PersonGender.BOY, 55, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array),
                    new PersonBean(32, 32, "jack", PersonGender.BOY, 55, DateTime.now(), 1L, Decimal.valueOf(1d), PersonBean.Flags, PersonBean.Array)), (p, x) -> p.name.equals(x.name), Tuple::of
    ));

    showResult("groupBy(p -> p.index2...", Linq.from(personSet).groupBy(p -> p.index2, (p, x) -> {
        System.out.println("groupKey: " + p);
        List<PersonBean> list = x.toList();
        System.out.println("items: " + toJsonString(list));
        return list.get(0);
    }));
    showResult("groupByMany(p -> new Object[] { p.index2, p.index3 })",
            Linq.from(personSet).groupByMany(p -> Arrays.toList(p.index, p.index2), (p, x) -> {
                System.out.println("groupKey: " + toJsonString(p));
                List<PersonBean> list = x.toList();
                System.out.println("items: " + toJsonString(list));
                return list.get(0);
            }));

    showResult("orderBy(p->p.index)", Linq.from(personSet).orderBy(p -> p.index));
    showResult("orderByDescending(p->p.index)", Linq.from(personSet).orderByDescending(p -> p.index));
    showResult("orderByMany(p -> new Object[] { p.index2, p.index })",
            Linq.from(personSet).orderByMany(p -> Arrays.toList(p.index2, p.index)));
    showResult("orderByDescendingMany(p -> new Object[] { p.index2, p.index })",
            Linq.from(personSet).orderByDescendingMany(p -> Arrays.toList(p.index2, p.index)));

    showResult("select(p -> p.index).reverse()",
            Linq.from(personSet).orderBy(p -> p.index).select(p -> p.index).reverse());

    showResult(".max(p -> p.index)", Linq.from(personSet).<Integer>max(p -> p.index));
    showResult(".min(p -> p.index)", Linq.from(personSet).<Integer>min(p -> p.index));

    showResult("take(0).average(p -> p.index)", Linq.from(personSet).take(0).average(p -> p.index));
    showResult("average(p -> p.index)", Linq.from(personSet).average(p -> p.index));
    showResult("take(0).sum(p -> p.index)", Linq.from(personSet).take(0).sum(p -> p.index));
    showResult("sum(p -> p.index)", Linq.from(personSet).sum(p -> p.index));
    showResult("sumMoney(p -> p.index)", Linq.from(personSet).sumDecimal(p -> Decimal.valueOf((double) p.index)));

    showResult("cast<IPerson>", Linq.from(personSet).<IPerson>cast());
    Linq<?> oq = Linq.from(personSet).cast().union(Arrays.toList(1, 2, 3));
    showResult("ofType(Integer.class)", oq.ofType(Integer.class));

    showResult("firstOrDefault()", Linq.from(personSet).orderBy(p -> p.index).firstOrDefault());
    showResult("lastOrDefault()", Linq.from(personSet).orderBy(p -> p.index).lastOrDefault());
    showResult("skip(2)", Linq.from(personSet).orderBy(p -> p.index).skip(2));
    showResult("take(2)", Linq.from(personSet).orderBy(p -> p.index).take(2));

    showResult(".skipWhile((p, i) -> p.index < 3)",
            Linq.from(personSet).orderBy(p -> p.index).skipWhile((p, i) -> p.index < 3));

    showResult(".takeWhile((p, i) -> p.index < 3)",
            Linq.from(personSet).orderBy(p -> p.index).takeWhile((p, i) -> p.index < 3));

    Linq<PersonBean> set0 = Linq.from(personSet);
    Linq<PersonBean> set1 = set0.take(1);
    System.out.printf("set a=%s,b=%s%n", set0.count(), set1.count());
    assert set0.count() > set1.count();
}
```

## NEvent

```java
@Test
public void runNEvent() {
    UserManagerImpl mgr = new UserManagerImpl();
    PersonBean p = PersonBean.YouFan;

    mgr.onCreate.tail((s, e) -> System.out.println("always tail:" + e));
    TripleAction<UserManager, UserEventArgs> a = (s, e) -> System.out.println("a:" + e);
    TripleAction<UserManager, UserEventArgs> b = (s, e) -> System.out.println("b:" + e);
    TripleAction<UserManager, UserEventArgs> c = (s, e) -> System.out.println("c:" + e);

    mgr.onCreate.combine(a);
    mgr.create(p);  //触发事件（a执行）

    mgr.onCreate.combine(b);
    mgr.create(p); //触发事件（a, b执行）

    mgr.onCreate.combine(a, b);  //会去重
    mgr.create(p); //触发事件（a, b执行）

    mgr.onCreate.remove(b);
    mgr.create(p); //触发事件（a执行）

    mgr.onCreate.replace(a, c);
    mgr.create(p); //触发事件（a, c执行）
}
```
