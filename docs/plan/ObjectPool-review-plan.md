# ObjectPool 实现 Review 与整改计划

> 目标：对 `org.rx.core.ObjectPool` 当前实现进行并发安全、资源生命周期、性能、可观测性和测试覆盖 review，并形成可执行整改计划。
>
> 范围：
> - `rxlib/src/main/java/org/rx/core/ObjectPool.java`
> - `rxlib/src/main/java/org/rx/core/IdentityWrapper.java`
> - `rxlib/src/test/java/org/rx/core/ObjectPoolTest.java`
> - 主要使用方：`RpcClientPool`、`RpcHybridClientPoolImpl`、`Socks5UpstreamPoolManager.UdpLeasePool`

---

## 1. 总体结论

当前 `ObjectPool<T>` 的设计方向是偏高性能场景的对象池：

- `FastThreadLocal` L1 本地缓存
- 共享 idle 双向链表
- live 双向链表扫描
- CAS 状态机
- 自适应 refill
- idleTimeout / maxLifetime / leakDetection
- DiagnosticMetrics 指标输出

整体思路可以保留，但当前实现不建议直接作为“强一致通用对象池”长期使用。普通 borrow/recycle、基础并发和当前测试用例大概率能通过，但在高并发、跨线程归还、池满等待、后台扫描、close 与 borrow 并发、泄漏检测等场景下，有若干较隐蔽的正确性风险。

优先修复方向：

| 优先级 | 问题 | 影响 |
|---|---|---|
| P0 | L1 ThreadLocal cache 会把 idle 对象藏在归还线程，其他线程不可见 | 池满时其他线程可能 borrow timeout |
| P0 | `doCreate()` 中对象先进入 `conf/live`，但状态仍是默认 `IDLE` | 后台扫描可能误 retire 正在创建/即将借出的对象 |
| P0 | `borrow()` / `doCreate()` 不检查 pool closed/dispose | pool close 后仍可能创建新对象 |
| P1 | `casState()` CAS 失败也更新 `stateTime/t/createTime` | 泄漏检测和 owner 线程诊断被污染 |
| P1 | `doRetire()` 释放容量后不唤醒等待 borrower | 池满等待线程可能睡到 timeout |
| P1 | 泄漏检测默认关闭 borrowed 对象 | 长请求/慢 RPC 可能被后台线程强制关闭 |
| P2 | `lookupKey FastThreadLocal` 保留最后 recycle 的对象引用 | 长生命周期线程下有额外强引用保留风险 |
| P2 | 后台 validate 可能和 borrow 并发作用在同一对象上 | `validateHandler` 非线程安全时有风险 |

---

## 2. P0：L1 ThreadLocal cache 跨线程不可见

### 2.1 现状

`recycle(c, true)` 在以下条件满足时，会把对象只放入当前线程的 `threadLocalCache`：

```java
allowThreadLocal && minIdleSize <= 0 && threadLocalCache.get() == null && waitingBorrowers.get() == 0
```

此时对象不会进入共享 idle 链表，`sharedIdleCount` 不增加，其他线程也无法借到这个 idle 对象。

### 2.2 问题场景

```java
ObjectPool<Object> pool = new ObjectPool<>(0, 1, Object::new, x -> true);

// Thread A
Object obj = pool.borrow();
pool.recycle(obj); // 没有 waiter，于是 obj 进入 A 的 ThreadLocal

// Thread B
pool.borrow();     // shared idle 为空，size == maxPoolSize，无法 create，最后 timeout
```

对象实际上已经 idle，但只对 Thread A 可见。Netty EventLoop、RPC 回调和业务线程混用时，这个问题会更明显。

### 2.3 整改建议

不要让 L1 cache 成为对象唯一持有路径。推荐语义：

- recycle 时对象始终进入 shared idle；
- ThreadLocal 只保存一个“快速 hint”；
- L1 borrow 成功后，必须从 shared idle 链表 unlink。

示意代码：

```java
void recycle(ObjectConf<T> c, boolean allowThreadLocal) {
    // validate/passivate 省略
    if (c.casState(ObjectConf.BORROWED, ObjectConf.IDLE)) {
        offerSharedIdle(c);

        if (allowThreadLocal && minIdleSize <= 0) {
            threadLocalCache.set(c); // 只是 hint，不是唯一可见路径
        }
        return;
    }
    // error handling...
}
```

L1 borrow：

```java
ObjectConf<T> c = minIdleSize > 0 ? null : threadLocalCache.get();
if (c != null) {
    threadLocalCache.set(null);
    if (conf.get(c.wrapper) == c && c.casState(ObjectConf.IDLE, ObjectConf.BORROWED)) {
        idleLock.lock();
        try {
            unlinkIdle0(c);
        } finally {
            idleLock.unlock();
        }
        // validate/activate 后返回
    }
}
```

### 2.4 验收测试

新增测试：

```java
@Test
void testThreadLocalCachedObjectVisibleToOtherThreadWhenPoolFull()
```

验证点：`maxPoolSize=1`，线程 A recycle 后，线程 B 必须能 borrow 到对象，不能 timeout。

---

## 3. P0：`doCreate()` 对象过早暴露给后台扫描

### 3.1 现状

当前 `doCreate()` 主要流程：

```java
totalCount + 1
wrapper = new IdentityWrapper<>(createHandler.get())
c = new ObjectConf<>()
conf.putIfAbsent(wrapper, c)
linkLive(c)
activateHandler.accept(...)
c.setBorrowed(true)
return c
```

`ObjectConf` 初始状态是 `IDLE`。对象在 `setBorrowed(true)` 之前已经进入 `conf` 和 live 链表。

### 3.2 风险

后台 `validNow()` 可能扫描到这个刚创建、但尚未设置为 `BORROWED` 的对象，误认为它是 idle：

- 可能执行 `validateHandler.test()`；
- 可能因为 `stateTime == 0` 被 idleTimeout 逻辑误判；
- 可能被 retire；
- borrow 线程随后拿到已 retired/closed 的对象。

### 3.3 整改建议

创建 `ObjectConf` 后先初始化状态，再放入 `conf/live`：

```java
ObjectConf<T> c = new ObjectConf<>();
c.wrapper = wrapper;
c.initState(ObjectConf.BORROWED); // 新增无 CAS 初始化方法

ObjectConf<T> prev = conf.putIfAbsent(wrapper, c);
if (prev != null) {
    totalCount.decrementAndGet();
    tryClose(wrapper);
    throw new InvalidException("Object '{}' has already in this pool", wrapper);
}
linkLive(c);
```

进一步建议拆分创建语义：

```java
doCreateBorrowed(); // borrow 快路径使用，返回 BORROWED 对象
doCreateIdle();     // minIdle/refill 使用，返回 IDLE 对象并入 shared idle
```

这样避免预热对象走 `activate -> recycle -> passivate` 的非必要流程。

### 3.4 验收测试

新增测试：

```java
@Test
void testCreateObjectNotRetiredBeforeBorrowedStateInitialized()
```

建议使用慢 `activateHandler` + 高频 `validNow()`，验证创建中的对象不会被后台扫描 retire。

---

## 4. P0：close 后仍可能 borrow/create

### 4.1 现状

`ObjectPool` 继承 `Disposable`，但 `borrow()` 没有调用 `checkNotClosed()`，`doCreate()` 也没有检查 `disposing/isClosed()`。

部分上层使用方自己做了保护，例如 `RpcClientPool.borrowClient()` 会先 `checkNotClosed()`；但并不是所有使用方都包了一层，例如 `Socks5UpstreamPoolManager.UdpLeasePool.borrow()` 是直接调用 `delegate.borrow()`。

### 4.2 风险

pool close 后，如果仍有外部持有 `ObjectPool` 或 delegate，理论上还可以继续 borrow，甚至创建新对象。

### 4.3 整改建议

`borrow()` 开头增加：

```java
public T borrow() throws TimeoutException {
    checkNotClosed();
    if (disposing) {
        throw new TimeoutException("pool disposing");
    }
    // ...
}
```

`doCreate()` 开头增加：

```java
if (disposing || isClosed()) {
    return null;
}
```

`dispose()` 中唤醒等待线程：

```java
idleLock.lock();
try {
    idleAvailable.signalAll();
} finally {
    idleLock.unlock();
}
```

### 4.4 验收测试

新增测试：

```java
@Test
void testBorrowAfterPoolCloseShouldFailAndNotCreate()
```

验证点：

- close 后 borrow 立即失败；
- createHandler 不再被调用；
- 阻塞中的 borrower 能尽快醒来，而不是等完整 borrowTimeout。

---

## 5. P1：`casState()` CAS 失败也有副作用

### 5.1 现状

当前 `casState()` 在 CAS 前就写入：

```java
if (update == BORROWED) {
    t = Thread.currentThread();
}
stateTime = System.nanoTime();
if (createTime == 0) {
    createTime = stateTime;
}
state.compareAndSet(expect, update)
```

### 5.2 风险

CAS 失败时也会更新 `t/stateTime/createTime`，导致：

- 泄漏检测的时间被刷新；
- owner thread 被错误覆盖；
- 诊断信息不可信；
- 某些竞争下 maxLifetime/createTime 语义被污染。

### 5.3 整改建议

CAS 成功后再写副作用字段：

```java
public boolean casState(int expect, int update) {
    long now = System.nanoTime();
    if (!state.compareAndSet(expect, update)) {
        return false;
    }
    stateTime = now;
    if (createTime == 0) {
        createTime = now;
    }
    if (update == BORROWED) {
        t = Thread.currentThread();
    } else if (update == IDLE || update == RETIRED) {
        t = null;
    }
    return true;
}
```

`setBorrowed()` 建议改成只用于初始化，运行期统一走 CAS，减少绕过状态机。

---

## 6. P1：`doRetire()` 释放容量后没有唤醒等待 borrower

### 6.1 现状

池满时 borrower 会在 `pollSharedIdle(waitTime)` 中等待 `Condition`。如果后台 validation/leak detection retire 了对象，`totalCount` 下降，但没有 signal 等待线程。

### 6.2 风险

场景：

```text
maxPoolSize 已满
没有 idle
线程 B 等待 borrow
后台 validNow retire 一个 invalid/leaked 对象
size 下降，但 B 没有被唤醒
B 可能一直等到 borrowTimeout
```

### 6.3 整改建议

`doRetire()` 成功释放容量后执行：

```java
idleLock.lock();
try {
    idleAvailable.signalAll();
} finally {
    idleLock.unlock();
}
```

并确保 `borrow()` 被唤醒后重新判断：

```java
if (size() < maxPoolSize) {
    c = doCreate();
}
```

### 6.4 验收测试

新增测试：

```java
@Test
void testRetireSignalsWaitingBorrowers()
```

验证点：等待中的 borrower 在 retire 释放容量后能尽快醒来并创建/借到对象。

---

## 7. P1：泄漏检测默认 close borrowed 对象风险偏大

### 7.1 现状

`validNow()` 检测到 borrowed 对象超过 `leakDetectionThreshold` 后，会调用：

```java
doRetire(c.wrapper, 4)
```

默认 `closeObjectOnLeak = true`，因此 borrowed 对象仍可能被后台线程直接 close。

### 7.2 风险

对于 `DefaultTcpClient`、`HybridClient`、`Socks5UdpLease` 这种真实网络资源对象，后台强制 close borrowed 对象可能导致业务线程出现难以定位的连接异常。

长请求、慢网络、调试暂停、RPC 卡顿都可能被误判成 leak。

### 7.3 整改建议

推荐默认策略改为只报警，不关闭：

```java
volatile boolean closeObjectOnLeak = false;
```

或者改为两阶段策略：

```text
第一次超过 threshold：标记 suspected leak + 记录指标/日志/owner thread
第二次仍未归还：按配置决定是否 retire/close
```

可新增字段：

```java
volatile int leakCloseAfterDetections = 0; // 0 表示永不自动 close
```

---

## 8. P2：`lookupKey FastThreadLocal` 保留对象强引用

### 8.1 现状

`recycle(T obj)` 使用 `lookupKey.get()` 获取可复用 `IdentityWrapper`，设置 `lk.instance = obj` 后用于 `conf.get(lk)`。

但查完后没有清空 `lk.instance`。

### 8.2 风险

Netty/EventLoop 线程生命周期很长，FastThreadLocal 会让每个线程额外保留最后一次 recycle 对象的强引用。

### 8.3 整改建议

查完后清空引用：

```java
public void recycle(@NonNull T obj) {
    IdentityWrapper<T> lk = lookupKey.get();
    ObjectConf<T> c;
    try {
        lk.instance = obj;
        c = conf.get(lk);
    } finally {
        lk.instance = null;
    }

    if (c == null) {
        if (!disposing && !isClosed()) {
            throw new InvalidException("Object '{}' does not belong to this pool", obj);
        }
        return;
    }
    recycle(c, true);
}
```

兼容性考虑：如果不想改变非法对象 recycle 的语义，可以第一阶段只做 `finally { lk.instance = null; }`。

---

## 9. P2：后台 validate 可能和 borrow 并发执行

### 9.1 现状

`validNow()` 通过 `c.isBorrowed()` 判断对象是否借出。如果当时看到不是 borrowed，就可能继续执行 `validateHandler.test()`。

但这个判断和 borrow 线程的 `IDLE -> BORROWED` CAS 之间没有强互斥。

### 9.2 风险

扫描线程可能对一个即将被借出/刚被借出的对象执行 validate。如果 validate 非线程安全或有副作用，可能出现并发问题。

当前使用方多为 `isConnected()`、`isActive()` 这类状态检查，风险较低，但 `ObjectPool<T>` 作为通用组件不应依赖这个假设。

### 9.3 整改建议

增加 `VALIDATING` 状态：

```java
static final int IDLE = 0, BORROWED = 1, RETIRED = 2, VALIDATING = 3;
```

后台扫描流程：

```java
if (c.casState(IDLE, VALIDATING)) {
    try {
        boolean ok = validateHandler.test(c.wrapper.instance);
        if (!ok || timeout || expired) {
            c.casState(VALIDATING, RETIRED);
            doRetire(c.wrapper, 3);
        } else {
            c.casState(VALIDATING, IDLE);
        }
    } catch (Throwable e) {
        c.casState(VALIDATING, RETIRED);
        doRetire(c.wrapper, 0);
    }
}
```

borrow 只允许：

```java
IDLE -> BORROWED
```

这样 validate 和 borrow 不会并发作用在同一对象上。

---

## 10. 自适应 refill 语义建议

当前 adaptive refill 根据最近 12 个周期 borrow 次数计算：

```java
int targetSize = Math.max(minIdleSize,
    Math.min(maxPoolSize, (int) Math.ceil(avgPerPeriod * demandFactor)));
```

当前 `targetSize` 实际代表的是 **target total size**，不是 target idle size。

建议指标和命名区分：

```java
targetTotalSize
sharedIdleSize
borrowedSize = totalCount - sharedIdleCount
```

如果修复 L1 cache 为“shared idle + local hint”，`idleSize()` 会更可信。

### 建议保留

- `borrowAccumulator`
- `borrowSamples`
- `demandFactor`
- 周期性 `validNow()` 中的 refill 逻辑

### 建议调整

- `insureTargetSize(target)` 明确命名为 `ensureTargetTotalSize(targetTotalSize)`；
- 预热路径使用 `doCreateIdle()`，避免 activate/passivate；
- 指标中记录 `target.total.count`，避免误解为空闲目标。

---

## 11. 推荐实施顺序

### Phase 1：正确性热修

目标：不大改架构，先消除最容易触发的并发错误。

1. `borrow()` / `doCreate()` 增加 close/dispose 检查。
2. `dispose()` / `doRetire()` 增加 `idleAvailable.signalAll()`。
3. `casState()` 改成 CAS 成功后再写 `stateTime/t/createTime`。
4. `lookupKey` 查完后清空 `instance`。
5. L1 cache 改成 shared idle + thread-local hint，不再独占对象。
6. 新增 P0/P1 对应单测。

### Phase 2：状态机重构

目标：让后台 validate、borrow、retire 之间的状态转换更严谨。

1. 增加 `VALIDATING` 状态。
2. 拆分 `doCreateBorrowed()` 和 `doCreateIdle()`。
3. 后台扫描 validate 前必须 `IDLE -> VALIDATING`。
4. leak detection 改为默认只报警，不默认 close borrowed 对象。
5. 补充高并发 stress test。

### Phase 3：API 与可观测性优化

目标：提升长期维护和诊断能力。

1. 指标命名区分 `targetTotalSize` / `idleSize` / `borrowedSize`。
2. 非法 recycle 是否抛异常做成配置项。
3. 增加 pool name/tag，避免只用 identityHashCode。
4. 文档更新 `docs/reference/ObjectPool.md`。
5. 对 RPC/UDP lease 使用方补充推荐配置说明。

---

## 12. 建议新增测试清单

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

---

## 13. 目标效果

整改完成后，`ObjectPool<T>` 应满足：

1. 任意线程归还的 idle 对象，对其他线程都可见；
2. close/dispose 后不会再创建新对象；
3. 创建中对象不会被后台扫描误判为 idle；
4. CAS 失败不会污染对象状态时间和 owner thread；
5. retire 释放容量后能唤醒等待 borrower；
6. leak detection 默认不破坏正在使用的对象；
7. validate 与 borrow 不会并发作用在同一个对象上；
8. adaptive refill 保留，但 total/idle 语义更清晰；
9. 在 RPC/UDP lease/Netty 高并发场景下，减少难复现的 borrow timeout、误关闭和对象隐藏问题。
