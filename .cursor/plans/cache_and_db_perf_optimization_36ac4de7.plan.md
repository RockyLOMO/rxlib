---
name: Cache and DB perf optimization
overview: "对 MemoryCache、H2StoreCache、EntityDatabaseImpl 进行性能优化。核心原则：不替换任何现有方法行为，所有性能改进通过\"新增 fast-path 方法\"或\"新增指标接口\"实现。Phase 1 直接推进零风险项；Phase 2 新增 fast-path + 内部改写；Phase 3 压测验证后决定。"
todos:
  - id: phase1-query-thread-safety
    content: "Phase 1: EntityDatabaseImpl.count()/exists() 不修改 query 状态，修复线程安全 bug"
    status: pending
  - id: phase1-setview-init
    content: "Phase 1: H2StoreCache.entrySet() setView 改为字段直接初始化"
    status: pending
  - id: phase1-singleton-params
    content: "Phase 1: EntityDatabaseImpl 单参数场景用 Collections.singletonList"
    status: pending
  - id: phase2-upsert-path
    content: "Phase 2: EntityDatabase 新增 upsert()，save() 完全不动；H2StoreCache 内部改用 upsert"
    status: pending
  - id: phase2-put-fast
    content: "Phase 2: H2StoreCache 新增 putFast()，put() 完全不动"
    status: pending
  - id: phase2-remove-fast
    content: "Phase 2: H2StoreCache 新增 removeFast()，remove() 完全不动"
    status: pending
  - id: phase2-sliding-throttle
    content: "Phase 2: H2StoreCache.get() 滑动续期 per-key 节流"
    status: pending
  - id: phase2-connpool-swap
    content: "Phase 2: EntityDatabaseImpl 连接池 volatile + swapPool() 统一协议"
    status: pending
  - id: phase2-policymap-reorder
    content: "Phase 2: MemoryCache computeNanos 反转查找顺序，policyMap 保留不删"
    status: pending
  - id: phase2-size-metric
    content: "Phase 2: H2StoreCache 新增 estimatedSize() 指标接口，size() 完全不动"
    status: pending
  - id: phase2-putfast-mc
    content: "Phase 2: MemoryCache 新增 putFast()，put() 完全不动"
    status: pending
  - id: phase3-stmt-cache
    content: "Phase 3: PreparedStatement 缓存，压测验证后决定"
    status: pending
  - id: regression-and-monitoring
    content: "贯穿: 每阶段完成后补回归测试 + 监控指标"
    status: pending
isProject: false
---

# 缓存与数据库层性能优化计划（v5 可执行版）

## 模式：高性能模式

## 核心原则

**不替换任何现有方法的行为。** 所有 `save()`、`put()`、`remove()`、`size()`、`put(key, value, policy)` 的签名、返回值、语义保持原样。性能改进一律通过以下两种方式实现：

1. **新增 fast-path 方法**：如 `upsert()`、`putFast()`、`removeFast()` — 内部调用方按需切换
2. **新增指标接口**：如 `estimatedSize()` — 监控消费者使用，`size()` 不变

---

## Phase 1 — 直接推进：零风险项

### 1.1 count() / exists() 不修改 query 状态

**问题**：直接修改传入 `query` 的 `orders`/`limit`/`offset` 再恢复，并发不安全。

**方案**：给 `EntityQueryLambda.resolve()` 新增 `resolveForCount(params)` 重载（或增加 `excludeOrders`/`excludeLimit` 标志位），在 SQL 拼接时跳过 ORDER BY 和 LIMIT。`count()` 和 `exists()` 改为调用新方法，不再操作 query 字段。

### 1.2 entrySet() setView 直接初始化

**方案**：`final EntrySetView setView = new EntrySetView()`，消除懒初始化竞态。

### 1.3 单参数 ArrayList 优化

**方案**：`deleteById`、`existsById`、`findById` 中 `new ArrayList<>(1) + add` 替换为 `Collections.singletonList(id)`。

### Phase 1 回归

- 多线程同一 query 并发调用 count() + findBy()，验证无 ConcurrentModificationException
- 现有 EntityDatabaseTest 全量通过

---

## Phase 2 — 新增 fast-path + 内部改写（不动任何现有方法）

### 2.1 EntityDatabase 新增 `upsert(T entity)`

**问题**：`save(T entity)` 在有主键时走 existsById(SELECT) + 事务 + 条件分支 = 3+ 次 DB。

**方案**：

- `EntityDatabase` 接口新增 `upsert(T entity)` 方法
- 实现：直接执行 `insertSql`（MERGE INTO），不做 existsById，不开事务，1 次 DB
- **`save()` 完全不动**，部分更新语义保留

```java
// EntityDatabaseImpl 新增
@Override
public <T> void upsert(T entity) {
    SqlMeta meta = getMeta(entity.getClass());
    List<Object> params = new ArrayList<>();
    for (Map.Entry<String, Tuple<Field, DbColumn>> col : meta.insertView) {
        params.add(col.getValue().left.get(entity));
    }
    executeUpdate(meta.insertSql, params);
}
```

- `H2StoreCache` 内部 3 处 `db.save(item)` 改为 `db.upsert(item)`（H2CacheItem 始终全字段，已审计确认安全）

### 2.2 H2StoreCache 新增 `putFast(TK key, TV value, CachePolicy policy)`

**问题**：`put()` 每次先 findById 取旧值（SELECT）再 save（existsById + INSERT/UPDATE）= 3+ 次 DB。

**方案**：

- **`put()` 完全不动**，保留 ConcurrentMap 返回旧值契约
- 新增 `putFast()`：不查旧值、不返回旧值，直接 upsert，1 次 DB

```java
public void putFast(TK key, TV value, CachePolicy policy) {
    if (policy == null) {
        policy = CachePolicy.absolute(defaultExpireSeconds);
    }
    H2CacheItem<TK, TV> newItem = new H2CacheItem<>(key, value, policy);
    newItem.setRegion(key.getClass().getSimpleName());
    l1Cache.put(key, newItem, newItem);
    db.upsert(newItem);
}
```

- `H2StoreCache.put()` 内部写路径从 `db.save(newItem)` 改为 `db.upsert(newItem)`（仅优化写入，旧值获取逻辑不变）：put 从 3 次 DB 降到 2 次
- 内部调用方如不需要旧值（如 `Cache.get(key, loadingFunc, policy)` 中的 miss 填充），可改调 `putFast()` 直接 1 次 DB

### 2.3 H2StoreCache 新增 `removeFast(Object key)`

**问题**：`remove()` 先 findById(SELECT) 再 deleteById(DELETE) = 2 次 DB。

**方案**：

- **`remove()` 完全不动**，保留 ConcurrentMap 返回旧值契约
- 新增 `removeFast()`：不查旧值、不返回旧值，直接 deleteById，1 次 DB

```java
public void removeFast(Object key) {
    l1Cache.remove(key);
    db.deleteById(H2CacheItem.class, CodecUtil.hash64(key));
}
```

- 内部调用方如不需要旧值（如 `expungeStale()` 已知要删的条目），可改调 `removeFast()`

### 2.4 滑动续期 per-key 节流

**问题**：每次 get 命中 sliding key 都异步 `db.save()`，无节流。

**方案**：`H2CacheItem` 增加 `transient volatile long lastDbRenewNanos`，get 路径中 per-key 节流：

```java
if (item.slidingRenew()) {
    long now = System.nanoTime();
    long elapsed = now - item.lastDbRenewNanos;
    if (elapsed > TimeUnit.MILLISECONDS.toNanos(item.getSlidingSpan() / 2)) {
        item.lastDbRenewNanos = now;
        final H2CacheItem<TK, TV> fItem = item;
        Tasks.run(() -> db.upsert(fItem));
    }
}
```

**保证**：内存态 expiration 每次 get 都更新；DB 侧在 slidingSpan/2 内至少写一次，expungeStale 不会误删。

### 2.5 连接池 volatile + swapPool 统一协议

**问题**：`getConnectionPool()` synchronized 导致串行竞争；时间滚动置空时未 dispose 旧池（资源泄漏）。

**方案**：

```java
volatile JdbcConnectionPool connPool;

JdbcConnectionPool getConnectionPool() {
    JdbcConnectionPool pool = connPool;
    if (pool != null) {
        return pool;
    }
    return initPool();
}

private synchronized JdbcConnectionPool initPool() {
    if (connPool == null) {
        // ... 创建并赋值 connPool ...
    }
    return connPool;
}

private synchronized void swapPool(JdbcConnectionPool newPool) {
    JdbcConnectionPool old = connPool;
    connPool = newPool;
    if (old != null) {
        old.dispose();
    }
}
```

全部 4 个写入点统一改为 swapPool：
- 时间滚动定时器：`swapPool(null)` — 修复资源泄漏
- preInvoke 错误恢复：`swapPool(null)` + `getConnectionPool()`
- dispose()：`swapPool(null)`

### 2.6 policyMap 查找顺序反转

**问题**：`computeNanos` 每次先查 `policyMap.get(key)`。

**约束**：`RandomList.next()`、`SocksTcpUpstream`、`DnsHandler` 等"普通值 + 外部 policy"场景必须依赖 policyMap。**不可删除 policyMap**。

**方案**：仅反转查找顺序——先 `as(value, CachePolicy.class)`，未命中再 `policyMap.get(key)`。

```java
long computeNanos(Object key, Object value, long currentDuration) {
    long ttlNanos;
    CachePolicy policy = as(value, CachePolicy.class);
    if (policy == null) {
        policy = policyMap.get(key);
    }
    // ... 后续不变
}
```

### 2.7 H2StoreCache 新增 `estimatedSize()` 指标接口

**问题**：`size()` 每次执行 COUNT SQL。

**方案**：

- **`size()` 完全不动**，保留精确 COUNT 语义
- 新增 `estimatedSize()`：维护 `AtomicLong` 近似计数，在 putFast/removeFast/expungeStale 时维护，expungeStale 周期中用 COUNT 校正
- 监控消费者使用 `estimatedSize()`，业务逻辑继续使用 `size()`

### 2.8 MemoryCache 新增 `putFast(TK key, TV value, CachePolicy policy)`

**问题**：`put()` 通过 `cache.asMap().put()` 返回旧值，比 `cache.put()` 多一次内部查找。

**方案**：

- **`put()` 完全不动**，保留 ConcurrentMap 返回旧值契约
- 新增 `putFast()`：使用 `cache.put(key, value)` 不返回旧值

```java
public void putFast(TK key, TV value, CachePolicy policy) {
    if (policy != null) {
        policyMap.put(key, policy);
    }
    cache.put(key, value);
}
```

- 内部调用方如不需要旧值（如 H2StoreCache 的 L1 缓存写入），可改调 `putFast()`

### Phase 2 回归

- **save() 稀疏更新不清空字段**：save 全字段 -> save(entity, false) 部分非 null -> 验证 DB 未赋值列保持原值
- **upsert() 全量覆写正确性**：upsert 后 findById 验证一致；upsert 已存在行验证覆盖
- **put() 返回旧值不变**：put 新 key 返回 null -> put 同 key 返回旧值 -> SetFromMap.add 行为验证
- **putFast() 不返回旧值**：putFast 后 get 验证值正确；putFast 同 key 覆盖验证
- **remove() 返回旧值不变**：remove 已有 key 返回旧值 -> remove 不存在 key 返回 null
- **removeFast() 无返回值**：removeFast 后 get 返回 null
- **热点 key 滑动续期不被误删**：put sliding key(2s) -> 多线程高频 get > 2s -> 手动 expungeStale -> key 仍存在
- **连接池 rollover 并发**：swapPool(null) 时有并发读写 -> 不抛 NPE、旧池 dispose、新池接管
- **连接池 closed-pool 恢复**：模拟 "Database is already closed" -> swapPool + 重建 -> 后续正常
- **普通值 + 外部 policy TTL 回归**：`put(key, "plain", CachePolicy.absolute(2))` -> 1s 后非 null -> 3s 后 null
- **size() 精确值不变**：put N 条 -> size() == N -> remove M 条 -> size() == N-M
- **estimatedSize() 近似值**：putFast/removeFast 后 estimatedSize 与 size 差值在可接受范围

---

## Phase 3 — 压测验证后决定

### 3.1 PreparedStatement 缓存

**问题**：每次查询都 `conn.prepareStatement(sql)` 创建新语句。

**方案**：先压测当前 H2 `QUERY_CACHE_SIZE` 配置下的 SQL 编译开销，确认是否为实际瓶颈。如果确认瓶颈：对高频固定 SQL（`existsByIdSql`、`findByIdSql`、`insertSql`、`deleteSql`）考虑连接级 PreparedStatement 缓存或调整 H2 参数。

---

## 四、监控指标（贯穿所有阶段）

- H2 连接池：活跃连接数 / 等待获取连接的线程数 / 连接获取耗时
- L1 缓存：命中率（hit/miss 比）、eviction 数
- DB QPS：按 SQL 类型统计（SELECT/MERGE/UPDATE/DELETE）
- 异步续期：当前排队任务数（Tasks executor queue size）
- 堆外内存占用（如涉及 Netty 调用链）

---

## 五、总览

- **Phase 1**（直接推进）— query 线程安全 + setView 初始化 + singletonList。3 项零风险改动。
- **Phase 2**（新增 fast-path + 内部改写）— upsert / putFast / removeFast / estimatedSize 全部是**新增方法**；policyMap 查找反转 / 滑动节流 / connpool swapPool 是**内部行为调整**。现有 save / put / remove / size / computeNanos **一行不改**。
- **Phase 3**（压测后定）— PreparedStatement 缓存，需先量化实际瓶颈。
