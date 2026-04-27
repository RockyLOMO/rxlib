## 顺序

### Snowflake

处理了时钟回拨的情况

```java
@Test
public void snowflake() {
    Set<Long> set = new HashSet<>();
    int len = 1 << 12;
    System.out.println(len);
    Snowflake snowflake = Snowflake.DEFAULT;
    for (int i = 0; i < len; i++) {
        Tasks.run(() -> {
            assert set.add(snowflake.nextId());
        });
    }
}
```

### Ordered UUID

基于[这篇文章](http://www.codeproject.com/Articles/388157/GUIDs-as-fast-primary-keys-under-multiple-database)思路改进。

## 随机

### CRC64

When using murmurHash3 128-bits, the x86 and x64 versions do not produce the same values. 故采用CRC64.

CRC64碰撞率低([详见](https://blog.csdn.net/huangyueranbbc/article/details/84393060))，自测2亿数据量没有碰撞，就算有碰撞那概率比写bug的概率还低～

```java
@Test
public void codec() {
    for (int i = 0; i < 10; i++) {
        long ts = System.nanoTime();
        assert App.orderedUUID(ts, i).equals(App.orderedUUID(ts, i));
    }

    EntityDatabase db = EntityDatabase.DEFAULT;
    db.createMapping(CollisionEntity.class);
    db.dropMapping(CollisionEntity.class);
    db.createMapping(CollisionEntity.class);
    int c = 200000000;
    AtomicInteger collision = new AtomicInteger();
    invoke("codec", i -> {
        long id = CrcModel.CRC64_ECMA_182.getCRC((UUID.randomUUID().toString() + i).getBytes(StandardCharsets.UTF_8)).getCrc();
        CollisionEntity po = db.findById(CollisionEntity.class, id);
        if (po != null) {
            log.warn("collision: {}", collision.incrementAndGet());
            return;
        }
        po = new CollisionEntity();
        po.setId(id);
        db.save(po, true);
    }, c);
    assert db.count(new EntityQueryLambda<>(CollisionEntity.class)) == c;
}
```

