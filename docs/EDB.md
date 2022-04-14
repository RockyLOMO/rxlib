基于H2实现实体类数据库，支持Sharding。

```java
@Test
public void h2Db() {
    EntityDatabaseImpl db = new EntityDatabaseImpl(h2Db, null);
      db.setAutoUnderscoreColumnName(true);
    db.createMapping(PersonBean.class);
    System.out.println(db.executeQuery("EXPLAIN select * from person"));

    db.begin();

    PersonBean entity = PersonBean.LeZhi;
    db.save(entity);

    EntityQueryLambda<PersonBean> queryLambda = new EntityQueryLambda<>(PersonBean.class).eq(PersonBean::getName, "乐之")
            .orderBy(PersonBean::getId)
            .limit(1, 10);
    assert db.exists(queryLambda);
    db.commit();

    System.out.println(db.executeQuery("select * from `person` limit 2", PersonBean.class));
    System.out.println(db.count(queryLambda));
    List<PersonBean> list = db.findBy(queryLambda);
    System.out.println(toJsonString(list));
    assert !list.isEmpty() && list.get(0).getName().equals("乐之");
    UUID pk = list.get(0).getId();
    assert db.existsById(PersonBean.class, pk);
    PersonBean byId = db.findById(PersonBean.class, pk);
    System.out.println(byId);
    assert byId != null;

    db.delete(new EntityQueryLambda<>(PersonBean.class).lt(PersonBean::getId, null));

    EntityQueryLambda<PersonBean> q = new EntityQueryLambda<>(PersonBean.class)
            .eq(PersonBean::getName, "张三")
            .in(PersonBean::getIndex, 1, 2, 3)
            .between(PersonBean::getAge, 6, 14)
            .notLike(PersonBean::getName, "王%");
    q.and(q.newClause()
            .ne(PersonBean::getAge, 10)
            .ne(PersonBean::getAge, 11))
            .or(q.newClause()
                    .ne(PersonBean::getAge, 12)
                    .ne(PersonBean::getAge, 13).orderByDescending(PersonBean::getMoney)).orderBy(PersonBean::getAge)
            .limit(100);
    System.out.println(q.toString());
    List<Object> params = new ArrayList<>();
    System.out.println(q.toString(params));
    System.out.println(toJsonString(params));

    System.out.println(q.orderByRand());
}
```

