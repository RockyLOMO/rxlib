---
name: H2StoreCache L1 同步优化
overview: 通过分段锁 (Striped ReentrantLock) 实现 H2StoreCache 中 L1 缓存与 H2 DB 之间的读写同步，在保证一致性的同时最大化并发吞吐。
todos:
  - id: add-striped-locks
    content: 在 H2StoreCache 中新增 ReentrantLock[] 分段锁基础设施（字段 + getLock 方法 + 构造函数初始化）
    status: pending
  - id: sync-read-path
    content: 改造 get() 和 containsPhysicalKey()：无锁快速路径 + Double-Check 慢路径
    status: pending
  - id: sync-write-path
    content: 改造 putPhysicalKey() 和 fastPutPhysicalKey()：锁内执行 L1+DB 写
    status: pending
  - id: sync-remove-path
    content: 改造 removePhysicalKey()：锁内执行 L1+DB 删
    status: pending
  - id: sync-expunge
    content: 改造 expungeStale()：per-item 获取 stripe lock 后清理
    status: pending
  - id: unit-test
    content: 补充并发测试用例：get+put、get+remove、put+put、expunge+get
    status: pending
isProject: false
---

# H2StoreCache L1 缓存读写同步 -- 高性能模式

## 1. 当前问题分析

[H2StoreCache.java](rxlib/src/main/java/org/rx/core/cache/H2StoreCache.java) 的 L1（Caffeine MemoryCache）与 L2（H2 DB）之间没有同步保护，存在以下竞态：

- **get() L1 miss + 并发 remove()**：Thread-A L1 未命中去查 DB；Thread-B 同时 remove 了 L1+DB；Thread-A 用 DB 旧数据回填 L1 -> **L1 出现幽灵条目**
- **get() L1 miss + 并发 put()**：Thread-A 从 DB 读到旧值并回填 L1；Thread-B 已写入新值到 L1+DB -> **L1 被旧值覆盖**
- **put() + put() 同 key 交叉写**：A 写 L1、B 写 L1、B 写 DB、A 写 DB -> **L1=valB 但 DB=valA，不一致**
- **get() expired 并发触发**：两个线程同时检测到过期，同时执行 DB 删除 + 事件触发 -> **重复清理/重复事件**
- **expungeStale() + get() 交叉**：清理线程删除 L1/DB 的间隙，get() 从 DB 读到即将被删的条目并回填 L1

## 2. 方案选型：Striped ReentrantLock（分段锁）

备选方案对比：

- **全局锁 / synchronized**：简单但吞吐崩塌，不可接受
- **ReadWriteLock**：读路径 L1 miss 时需要写 L1，无法只用读锁，退化为写锁
- **Caffeine compute()**：在 Caffeine 内部锁中执行 DB I/O，阻塞时间不可控，有死锁风险
- **Striped ReentrantLock（选定）**：固定内存开销、per-key 级并发度、不同 key 零竞争、Java 8 兼容

## 3. 核心设计

### 3.1 锁基础设施

在 `H2StoreCache` 中新增：

```java
private static final int LOCK_STRIPE_COUNT = 64;
private static final int LOCK_STRIPE_MASK = LOCK_STRIPE_COUNT - 1;
private final ReentrantLock[] locks;

ReentrantLock getLock(Object key) {
    int h = key.hashCode();
    h ^= (h >>> 16);
    return locks[h & LOCK_STRIPE_MASK];
}
```

构造函数中初始化 64 个锁。64 条 stripe 在绝大多数场景下竞争率极低，每个锁对象仅 ~48 bytes，总开销约 3KB。

### 3.2 读路径优化：Double-Check 快速路径

`get()` 和 `containsPhysicalKey()` 采用 **无锁快速路径 + 有锁慢路径** 双重检查：

```
get(key):
  1. 无锁读 L1 -> 命中且未过期 -> 直接返回（热点路径零锁开销）
  2. L1 未命中 或 已过期 -> 进入 getLock(key).lock()
     a. Double-check: 再次读 L1（可能被其他线程已回填）
     b. 仍未命中 -> 读 DB -> 未过期则回填 L1
     c. 过期 -> 清理 L1 + DB + 触发事件
  3. unlock()
```

### 3.3 写路径

`putPhysicalKey()` 和 `fastPutPhysicalKey()`：整个操作在 `getLock(physicalKey)` 保护下执行，确保 L1 写 + DB 写原子性。

### 3.4 删除路径

`removePhysicalKey()`：整个操作在锁保护下执行，确保 L1 删 + DB 删原子性，阻止并发 get() 回填。

### 3.5 过期清理

`expungeStale()`：对每个过期条目单独获取其 key 对应的 stripe lock 后再执行 L1 + DB 清理，避免与并发读写冲突。

## 4. 变更范围

仅修改 **1 个文件**：[H2StoreCache.java](rxlib/src/main/java/org/rx/core/cache/H2StoreCache.java)

- 新增字段：`ReentrantLock[] locks` + 常量
- 新增方法：`getLock(Object key)`
- 改造方法：`get()`、`containsPhysicalKey()`、`putPhysicalKey()`、`fastPutPhysicalKey()`、`removePhysicalKey()`、`expungeStale()`
- `clear()` 无需改造（`l1Cache.clear()` + `db.truncateMapping()` 本身是各自原子的，且 clear 是低频管理操作）

## 5. 性能特征

- **L1 命中热路径**：零锁开销，与当前完全一致
- **L1 miss / 写 / 删**：per-key stripe lock，不同 key 并行无阻塞
- **内存开销**：仅 64 个 ReentrantLock 对象，约 3KB
- **无额外对象分配**：锁数组在构造时一次性分配，运行时零分配

## 6. 测试验证

补充 / 改造单元测试 [H2StoreCacheTest.java](rxlib/src/test/java/org/rx/core/cache/H2StoreCacheTest.java)，覆盖：
- 并发 get + put 同 key：验证 L1/DB 一致性
- 并发 get + remove 同 key：验证无幽灵条目
- 并发 put + put 同 key：验证最终一致
- 过期清理 + 并发读：验证无 stale 回填
