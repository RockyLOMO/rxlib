# ObjectPool 实现 Review 与整改计划

> 文档状态：已根据第二轮修复后代码重新 review，并更新整改进度。
>
> Review 范围：
> - `rxlib/src/main/java/org/rx/core/ObjectPool.java`
> - `rxlib/src/main/java/org/rx/core/IdentityWrapper.java`
> - `rxlib/src/test/java/org/rx/core/ObjectPoolTest.java`
> - 主要使用方：`RpcClientPool`、`RpcHybridClientPoolImpl`、`Socks5UpstreamPoolManager.UdpLeasePool`

---

## 1. 第二轮 Review 总体结论

本轮修复覆盖比较完整，上一版提出的 P0/P1 主问题基本已经落地：

- L1 ThreadLocal cache 已改为 **shared idle + thread-local hint**，不再独占 idle 对象；
- `ObjectConf` 增加 `VALIDATING` 状态；
- `casState()` 已改为 CAS 成功后再更新 `stateTime/createTime/t`；
- 创建路径增加 `initState()`，避免对象以默认 `IDLE` 暴露；
- 创建借出路径与创建 idle 路径已拆分为 `doCreate()` / `doCreateIdle()`；
- `borrow()` / `reserveSlot()` / `pollSharedIdle()` 增加 close/dispose 判断；
- `dispose()` 和 `doRetire()` 已增加 borrower 唤醒；
- leak detection 默认改为只报警，不自动 close borrowed 对象；
- `lookupKey FastThreadLocal` 查找后已清空 `instance`；
- 新增了多项针对性测试。

当前版本整体比上一版安全很多，可以进入下一阶段：**小范围边界修复 + 压测验证 + 指标语义优化**。

---

## 2. 修复进度表

| 优先级 | 原问题 | 当前状态 | 复核结论 |
|---|---|---|---|
| P0 | L1 ThreadLocal cache 会隐藏 idle 对象 | 已修复 | recycle 始终 `offerSharedIdle(c)`，ThreadLocal 只做 hint；L1 borrow 成功后 `removeSharedIdle(c)` |
| P0 | 创建中对象以默认 `IDLE` 暴露 | 已修复 | `doCreate()` 创建 borrowed 对象时使用 `initState(BORROWED)`，且 activate 在进入 `conf/live` 前执行 |
| P0 | close 后仍可能 borrow/create | 已修复 | `checkBorrowable()`、`reserveSlot()`、`pollSharedIdle()` 均检查 close/dispose |
| P1 | CAS 失败污染状态时间/owner thread | 已修复 | `casState()` 先 CAS，成功后再写状态时间和 owner |
| P1 | retire 释放容量后不唤醒 borrower | 已修复 | `doRetire()` 调用 `signalBorrowers()` |
| P1 | 泄漏检测默认关闭 borrowed 对象 | 已修复 | `closeObjectOnLeak` 默认改为 `false`，默认只记录 leak 指标 |
| P2 | `lookupKey` 保留最后 recycle 对象强引用 | 已修复 | `finally { lk.instance = null; }` 已加入 |
| P2 | 后台 validate 可能和 borrow 并发 | 已修复 | 新增 `VALIDATING` 状态，扫描前 `IDLE -> VALIDATING` |
| P2 | adaptive refill 目标语义不够清晰 | 部分保留 | 逻辑可用，但指标/命名仍建议优化为 targetTotalSize |

---

## 3. 已确认的关键修复点

### 3.1 L1 cache 已从“独占持有”改成“本地 hint”

当前 `recycle(ObjectConf<T> c, boolean allowThreadLocal)` 在对象成功从 `BORROWED -> IDLE` 后，会先发布到 shared idle：

```java
if (c.casState(ObjectConf.BORROWED, ObjectConf.IDLE)) {
    offerSharedIdle(c);
    if (allowThreadLocal && minIdleSize <= 0 && threadLocalCache.get() == null) {
        threadLocalCache.set(c);
    }
}
```

同线程下一次 borrow 仍可先走 `threadLocalCache`，但对象同时对其他线程可见，避免了 `maxPoolSize=1` 时其他线程 borrow timeout 的问题。

已新增测试：

```java
@Test
public void testThreadLocalCachedObjectVisibleToOtherThreadWhenPoolFull()
```

### 3.2 状态机增加 `VALIDATING`

当前状态：

```java
static final int IDLE = 0, BORROWED = 1, RETIRED = 2, VALIDATING = 3;
```

后台扫描 idle 对象前先 CAS：

```java
if (!c.casState(ObjectConf.IDLE, ObjectConf.VALIDATING)) {
    continue;
}
removeSharedIdle(c);
```

validate 成功后再 `VALIDATING -> IDLE` 并重新放回 shared idle。这样可避免 validate 和 borrow 并发作用在同一个对象上。

已新增测试：

```java
@Test
public void testValidationStatePreventsBorrowWhileValidating()
```

### 3.3 创建路径已拆分

当前有两个创建入口：

```java
ObjectConf<T> doCreate()      // borrow 路径，创建 BORROWED 对象
ObjectConf<T> doCreateIdle()  // minIdle/refill 路径，创建 IDLE 对象并进入 shared idle
```

这个拆分是正确方向，避免预热对象走 `activate -> recycle -> passivate` 这种不自然路径。

已新增测试：

```java
@Test
public void testCreateObjectNotRetiredBeforeBorrowedStateInitialized()
```

### 3.4 close/dispose 防护已增强

当前新增：

```java
void checkBorrowable() throws TimeoutException {
    checkNotClosed();
    if (disposing) {
        throw new TimeoutException("pool disposing");
    }
}
```

`borrow()` 开始、循环中、返回对象前均会检查。`reserveSlot()` 也检查 `disposing || isClosed()`，避免 close 后继续创建。

已新增测试：

```java
@Test
public void testBorrowAfterPoolCloseShouldFailAndNotCreate()

@Test
public void testBlockedBorrowerWakesUpWhenPoolClosed()
```

### 3.5 retire 唤醒等待者

当前 `doRetire()` 在 `totalCount.decrementAndGet()` 后调用：

```java
signalBorrowers();
```

池满等待 borrower 能在容量释放后醒来。

已新增测试：

```java
@Test
public void testRetireSignalsWaitingBorrowers()
```

### 3.6 leak detection 默认只报警

当前默认值：

```java
volatile boolean closeObjectOnLeak = false;
```

`validNow()` 检测到 borrowed 超过阈值后，默认只记录 `OBJECT_POOL_LEAK` 指标；只有显式 `setCloseObjectOnLeak(true)` 才 retire/close。

已新增测试：

```java
@Test
public void testLeakDetectionDefaultDoesNotRetireBorrowedObject()
```

---

## 4. 本轮新增发现与建议

### 4.1 P1：创建失败时可能出现较紧的重试循环

#### 现象

当前 `pollSharedIdle(long timeout)` 中有这段逻辑：

```java
if (size() < maxPoolSize) {
    return null;
}
```

这个逻辑本意是：如果池还有容量，不必继续等 idle，直接返回让上层创建新对象。

但在 `borrow()` 中，如果 `doCreate()` 因 `createHandler` 瞬时失败而返回 `null`，随后会执行：

```java
long waitTime = (size() < maxPoolSize) ? Math.min(100, remainingTime) : remainingTime;
c = doPoll(waitTime);
```

由于 `size() < maxPoolSize`，`pollSharedIdle(waitTime)` 会立即返回 `null`，并不会真正 sleep/wait。于是当 `createHandler` 持续失败时，borrow 循环可能在 `borrowTimeout` 内较高频重试，造成 CPU 空转和日志刷屏。

#### 建议修复

方式一：在创建失败后显式 backoff。

```java
if (c == null) {
    if (size() < maxPoolSize) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(Math.min(100, remainingTime)));
    } else {
        c = doPoll(remainingTime);
    }
}
```

方式二：区分“因为池满需要等待”和“因为创建失败需要退避”。

推荐新增局部变量：

```java
boolean createAttempted = false;
boolean createFailed = false;
```

当 `size() < maxPoolSize` 且 `doCreate()` 返回 null 时，如果确认不是 close/dispose，则执行短暂 backoff。

#### 建议测试

```java
@Test
void testCreateHandlerContinuousFailureBackoff()
```

验证点：

- createHandler 持续抛异常；
- borrow 在 borrowTimeout 后失败；
- 创建尝试次数不能过高，例如 500ms 内不应达到数万次；
- 日志不应被高频刷爆。

---

### 4.2 P2：duplicate object 分支应 close wrapper.instance

#### 现象

`doCreate()` / `doCreateIdle()` 中如果 `createHandler` 返回了池中已存在的同一个对象，`conf.putIfAbsent(wrapper, c)` 会返回 `prev != null`。

当前处理是：

```java
releaseReservedSlot();
log.warn("Object '{}' has already in this pool", wrapper);
return null;
```

但这个分支没有 `tryClose(wrapper)`。

正常情况下这个分支极少发生，因为 `createHandler` 应该返回新对象。但作为防御逻辑，既然已经识别出 createHandler 异常返回重复对象，建议释放 slot 后也执行 `tryClose(wrapper)`，或者至少不要让该对象处于未知状态。

#### 建议修复

```java
if (prev != null) {
    releaseReservedSlot();
    tryClose(wrapper);
    log.warn("Object '{}' has already in this pool", wrapper);
    return null;
}
```

`doCreate()` 和 `doCreateIdle()` 两处都建议补齐。

---

### 4.3 P2：`doCreateIdle()` 对新对象直接 passivate，需要确认 handler 语义

当前 `doCreateIdle()` 创建对象后执行：

```java
if (!validateHandler.test(wrapper.instance)) {
    releaseReservedSlot();
    tryClose(wrapper);
    return null;
}
if (passivateHandler != null) {
    passivateHandler.accept(wrapper.instance);
}
```

这意味着预热对象没有经历 activate，但会直接 passivate。

这通常是合理的，因为 passivate 可以理解为“放入池前清理状态”。但如果某些使用方的 passivate 隐含依赖 activate 已执行，则可能有语义不匹配。

当前主要使用方：

- `RpcClientPool`：passivate 用于清理事件处理器、关闭 reconnect；
- `RpcHybridClientPoolImpl`：passivate 用于关闭 reconnect、reset handlers；
- `UdpLeasePool`：无 passivate。

建议补充约定：

> `passivateHandler` 必须允许作用在新创建且未 activate 的对象上。它的语义是“进入 idle 前重置/清理”，不是“借用结束后的反向操作”。

也可以在文档里明确：

```text
create -> validate -> passivate -> idle
idle -> activate -> borrowed
borrowed -> validate -> passivate -> idle
```

---

### 4.4 P3：指标命名仍建议优化

当前指标：

```java
rx.object_pool.target.count
```

实际含义是 target total size，不是 target idle size。建议改名或新增：

```java
rx.object_pool.target.total.count
rx.object_pool.borrowed.count
rx.object_pool.idle.count
```

当前 `borrowed.count` 可以估算为：

```java
Math.max(0, size() - idleSize())
```

注意：存在 `VALIDATING` 状态时，这个值并不完全等于 borrowed，严格来说可以命名为 `active.count` 或补充状态统计。

---

## 5. 当前剩余任务

### 5.1 必做

1. 给 create failure 场景增加 backoff，避免 `createHandler` 持续失败时 CPU 空转。
2. `doCreate()` / `doCreateIdle()` 的 duplicate object 分支补 `tryClose(wrapper)`。
3. 对 `ObjectPoolTest` 补 `testCreateHandlerContinuousFailureBackoff()`。

### 5.2 建议做

1. 更新 `docs/reference/ObjectPool.md`，同步新的状态机和生命周期语义。
2. 明确 `passivateHandler` 可以作用于新创建未 activate 对象。
3. 指标命名从 `target.count` 调整为 `target.total.count`，或者保留旧指标同时新增新指标。
4. 增加 pool name/tag 配置，避免只用 `identityHashCode`。
5. 增加一次高并发 stress test，覆盖：borrow/recycle/validNow/close 并发。

---

## 6. 建议新增/保留的测试清单

已新增并应保留：

```java
@Test
void testThreadLocalCachedObjectVisibleToOtherThreadWhenPoolFull()
```

```java
@Test
void testBorrowAfterPoolCloseShouldFailAndNotCreate()
```

```java
@Test
void testBlockedBorrowerWakesUpWhenPoolClosed()
```

```java
@Test
void testRetireSignalsWaitingBorrowers()
```

```java
@Test
void testCasStateFailureDoesNotUpdateOwnerThreadOrStateTime()
```

```java
@Test
void testCreateObjectNotRetiredBeforeBorrowedStateInitialized()
```

```java
@Test
void testLookupKeyDoesNotRetainRecycledObject()
```

```java
@Test
void testValidationStatePreventsBorrowWhileValidating()
```

```java
@Test
void testLeakDetectionDefaultDoesNotRetireBorrowedObject()
```

建议新增：

```java
@Test
void testCreateHandlerContinuousFailureBackoff()
```

```java
@Test
void testDuplicateCreatedObjectIsClosedOrRejectedCleanly()
```

```java
@Test
void testStressBorrowRecycleValidateAndCloseRace()
```

---

## 7. 最终目标

整改完成后，`ObjectPool<T>` 应满足：

1. 任意线程归还的 idle 对象，对其他线程都可见；
2. close/dispose 后不会再创建新对象；
3. 创建中对象不会被后台扫描误判为 idle；
4. CAS 失败不会污染对象状态时间和 owner thread；
5. retire 释放容量后能唤醒等待 borrower；
6. leak detection 默认不破坏正在使用的对象；
7. validate 与 borrow 不会并发作用在同一个对象上；
8. adaptive refill 保留，但 total/idle/borrowed 语义更清晰；
9. createHandler 故障时有退避机制，不会 tight loop；
10. 在 RPC/UDP lease/Netty 高并发场景下，减少难复现的 borrow timeout、误关闭和对象隐藏问题。

---

## 8. Review 结论

本轮修复质量较高，上一版最危险的并发正确性问题已经基本解决。

当前不建议再做大规模重构，优先做三个小补丁：

1. create failure backoff；
2. duplicate object 分支 close；
3. 文档/指标语义同步。

完成后可以进入压测阶段，重点压测：

- `maxPoolSize` 很小、线程数远大于池大小；
- `createHandler` 间歇失败；
- `validateHandler` 慢/阻塞；
- `validNow()` 与 borrow/recycle 并发；
- close 与阻塞 borrow 并发；
- RPC client / UDP lease 真实使用场景。
