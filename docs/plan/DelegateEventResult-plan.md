# Delegate 事件 API 简化与排序改造执行记录

## 本次模式

**高性能模式（Netty 底层网络编程）**

## 1. 最新决策

- `EventArgs` 不改接口，继续保留当前 class 形态。
- 暂不新增 `EventResult<TEvent>`，也不新增 `raiseEventResult`。
- 事件发布 API 从 `raiseEvent` / `raiseEventAsync` 改为 `publishEvent` / `publishEventAsync`。
- `Delegate.combine(...)` 改为 `Delegate.add(...)`，本项目内不保留 `combine` 兼容名。
- `Delegate.first(...)` / `last(...)` 不再作为独立 API，改用 `add(int order, delegate)`。
- `Delegate.close()` 合并到 `purge(boolean close)`，释放 handler 时调用 `purge(true)`。
- 普通监听器容器从 `CopyOnWriteArraySet` 改为 `CopyOnWriteArrayList`，新增时去重并按 order 排序。
- 阶段 3 网络链路语义不额外调整，只做 API 名迁移。

## 2. 当前 API 形态

### 2.1 Delegate 排序常量

```java
public static final class Order {
    public static final int First = Integer.MIN_VALUE;
    public static final int Default = 0;
    public static final int Last = Integer.MAX_VALUE;
}
```

### 2.2 Delegate 注册 API

```java
public final Delegate<TSender, TEvent> add(TripleAction<TSender, TEvent> delegate);

public final Delegate<TSender, TEvent> add(int order, TripleAction<TSender, TEvent> delegate);

public final Delegate<TSender, TEvent> add(TripleAction<TSender, TEvent>... delegates);

public final Delegate<TSender, TEvent> add(int order, TripleAction<TSender, TEvent>... delegates);
```

单个值重载用于避免单 handler 注册时产生 varargs array。

### 2.3 清理 API

```java
public Delegate<TSender, TEvent> purge();

public Delegate<TSender, TEvent> purge(boolean close);
```

- `purge()`：只清空监听器。
- `purge(true)`：先 `tryClose` 每个监听器，再清空。

## 3. 行为约束

- 相同 handler 重复 `add` 时先移除旧项，再按新 order 插入。
- 相同 order 下保持插入顺序。
- `EventArgs.cancel` 或 `EventArgs.handled` 后停止后续 handler。
- `Order.Last` 不再是单独 finally-like tail hook，而是普通有序 handler；如果前序 handler 取消或处理完成，后续 `Last` 也不会执行。
- 异步发布仍通过 `EventPublisher.asyncScheduler()` 调度。
- I/O 线程不得阻塞等待 `publishEventAsync(...).get()`。

## 4. 已完成代码范围

- `org.rx.core.Delegate`
- `org.rx.core.EventPublisher`
- 项目内 `combine` 调用迁移为 `add`
- 项目内 `raiseEvent` / `raiseEventAsync` 调用迁移为 `publishEvent` / `publishEventAsync`
- 项目内 `first` / `last` 调用迁移为 `add(Delegate.Order.First/Last, ...)`
- `ShellCommand` 中 `onPrintOut.close()` 改为 `onPrintOut.purge(true)`
- `Remoting` 事件代理方法名识别改为 `publishEvent` / `publishEventAsync`

## 5. 验证

新增 `DelegateTest` 覆盖：

- `add` 按 order 排序。
- 重复 handler 去重并按最新 order 重排。
- `purge(true)` 会关闭可关闭 handler。

已执行：

```text
mvn -pl rxlib -DskipTests compile
mvn -pl rxlib "-Dtest=org.rx.core.DelegateTest" test
```

## 6. 监控与风险

本次没有改变网络事件触发时机，只改变 API 名称和本地 Delegate 容器结构。

仍需持续关注：

- 异步事件线程池队列积压。
- handler 执行耗时。
- 事件取消/处理导致的后续 handler 跳过。
- Netty 堆外内存占用、连接数、吞吐和端到端延迟。
