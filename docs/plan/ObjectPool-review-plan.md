# ObjectPool 修改验证与后续计划

## 背景

用户要求验证 `ObjectPool` 的修改，并更新本计划文档。

本次采用 **高性能模式（Netty 底层网络编程）**。当前项目强制基线仍为 Java 8，验证重点放在对象池状态机、并发归还、创建失败退避、资源释放、可观测性，以及对网络池化场景的影响。

## 当前结论

`rxlib/src/main/java/org/rx/core/ObjectPool.java` 当前实现已经覆盖上一轮 review 中的主要修复点：

- `ObjectConf` 状态机包含 `IDLE / BORROWED / RETIRED / VALIDATING`。
- `recycle()` 先通过 `BORROWED -> VALIDATING` CAS 获取归还所有权，只有 CAS 成功线程才执行 `validateHandler` 与 `passivateHandler`。
- `validNow()` 校验 idle 对象前先切到 `VALIDATING` 并从 shared idle 队列摘除，避免 borrow 与后台 validate 并发作用同一对象。
- `borrow()` 在 `createHandler` 持续失败或 duplicate 创建失败时调用 `backoffCreateFailure()`，避免 borrowTimeout 内 tight loop。
- `doCreate()` 与 `doCreateIdle()` 的 duplicate object 分支会释放预占 slot，并调用 `tryClose(wrapper)`。
- `demandFactor` 已是 `volatile double`，满足 setter 与后台 `validNow()` 并发可见性。
- `lookupKey FastThreadLocal` 在查找后通过 `finally` 清空，降低长生命周期线程强引用保留风险。
- `threadLocalCache` 只作为 L1 hint，归还对象仍进入 shared idle，borrow 命中 hint 后仍需 `IDLE -> BORROWED` CAS。
- `closeObjectOnLeak=false` 为默认值，泄漏检测默认只记录指标，不强制关闭 borrowed 对象。
- 诊断指标已包含 `active.count`、`target.total.count`，并保留兼容旧名 `target.count`。
- 支持 `setName(String)` 给对象池添加诊断 tag，便于多池实例区分。

本轮复核确认过一个 P1 问题：`doCreateIdle()` 遇到 duplicate object 时只回滚计数并返回 `null`，没有记录失败冷却状态。该问题会让 `validNow()`、`insureTargetSize()`、`insureMinIdle()` 与 `triggerMinIdleMaintain()` 后续再次触发 idle 创建，导致同一个错误 `createHandler` 在短时间内被多次调用。

当前已完成修复：idle 预热/维护创建路径新增 100ms 失败冷却。`doCreateIdle()` 的 duplicate、validate failed、异常路径会设置冷却；`insureMinIdle()`、`insureTargetSize()`、`triggerMinIdleMaintain()` 在冷却期内跳过补建；idle 创建成功后清除冷却。borrow 直接创建路径仍使用原有 `backoffCreateFailure()`，避免把借用热路径和后台 idle 维护冷却强耦合。

## 已核对文件

- `rxlib/src/main/java/org/rx/core/ObjectPool.java`
- `rxlib/src/test/java/org/rx/core/ObjectPoolTest.java`
- `rxlib/src/test/java/org/rx/net/socks/Socks5SessionPoolTest.java`
- `docs/reference/ObjectPool.md`

## 已验证命令

### ObjectPool 单元测试

```powershell
mvn -pl rxlib -Dtest=ObjectPoolTest test
```

结果：

- `Tests run: 34`
- `Failures: 0`
- `Errors: 0`
- `Skipped: 0`
- `BUILD SUCCESS`

说明：

- 测试日志中出现的 `Object has already in this pool` 与 `doCreate error` WARN 是用例主动覆盖 duplicate object 与 create failure 分支，属于预期日志。
- 修复后完整 `ObjectPoolTest` 已稳定通过本轮回归。

### duplicate idle 单用例复核

```powershell
mvn -pl rxlib -Dtest=ObjectPoolTest#testDuplicateIdleCreatedObjectIsClosedAndRetiredByValidation test
```

结果：

- `Tests run: 1`
- `Failures: 0`
- `Errors: 0`
- `Skipped: 0`
- `BUILD SUCCESS`

结论：

- P1 duplicate idle 维护路径反复补建问题已修复。
- 该用例修复前单独运行失败，修复后单独运行通过。

### Socks5 session pool 回归

```powershell
mvn -pl rxlib -Dtest=Socks5SessionPoolTest test
```

结果：

- `Tests run: 4`
- `Failures: 0`
- `Errors: 0`
- `Skipped: 0`
- `BUILD SUCCESS`

说明：

- 该用例覆盖网络侧 session pool 使用路径，可作为 ObjectPool 修改后对 SOCKS 池化场景的轻量回归。

## 当前测试覆盖

`ObjectPoolTest` 当前已覆盖：

- 基础 borrow / recycle / validate 生命周期。
- 创建失败后恢复。
- 创建持续失败退避：`testCreateHandlerContinuousFailureBackoff()`。
- duplicate borrowed 创建拒绝与关闭：`testDuplicateCreatedObjectIsClosedOrRejectedCleanly()`。
- duplicate idle 创建关闭并在 validate 后 retire：`testDuplicateIdleCreatedObjectIsClosedAndRetiredByValidation()`。
- shared idle 与 ThreadLocal hint 可见性。
- stale ThreadLocal cache 不借出 retired 对象。
- close / dispose 唤醒等待 borrower。
- leak detection 默认不关闭 borrowed 对象。
- borrow / recycle / validNow / close race 压力 smoke：`testStressBorrowRecycleValidateAndCloseRace()`。

## 风险评估

### 并发与状态机

当前 `recycle()` 的状态所有权已经收紧，重复 recycle 或并发 recycle 只有一个线程能进入 `VALIDATING` 并执行 handler。该修复降低了重复 passivate、重复释放 ByteBuf / Channel 关联资源、重复清理 UDP lease 的风险。

仍需继续关注：

- handler 内部不得阻塞 EventLoop。
- handler 内部不得执行不可重入的重复释放逻辑。
- 若池对象封装 Netty `ByteBuf`，必须由调用方保证引用计数成对释放。

### 创建失败退避

`backoffCreateFailure()` 当前使用 `LockSupport.parkNanos()` 做 1ms 到 100ms 的退避，并在检测到中断后抛出 `InterruptedException`。这能避免 `createHandler` 持续失败时 CPU tight loop。

仍需继续关注：

- 连续失败时 WARN 日志可能较多，生产环境应结合日志采样或指标告警。
- 如果 `createHandler` 失败原因来自远端不可达，应通过池外熔断或上游摘除处理，不能只依赖对象池退避。

### duplicate object 语义

当前 duplicate 分支会关闭 `createHandler` 返回的重复对象。这是对错误 `createHandler` 的防御，但如果 `createHandler` 违规返回仍被业务持有的池内对象，关闭动作可能影响正在使用的对象。

结论：

- 保持当前防御逻辑。
- 文档必须明确：`createHandler` 必须返回全新对象，不得返回已在池内或仍被业务持有的对象。

新增确认问题：

- 修复前：`doCreateIdle()` 的 duplicate 分支没有设置失败冷却。
- 修复前：`insureTargetSize()` 收到 `null` 会退出当前 while，但不会阻止后续 `validNow()` 或 `triggerMinIdleMaintain()` 再次进入。
- 修复前：`doRetire()` 在非 disposing 场景会触发 `triggerMinIdleMaintain()`，当 duplicate 分支关闭了已有 idle 对象后，validate retire 会进一步放大补建重试。
- 影响：不会直接借出重复对象，但会造成短时间重复创建、重复 WARN、额外 close 调用和 CPU/日志噪声。

已完成修复：

- 增加 `volatile long idleCreateBackoffUntilNanos`。
- 增加 `IDLE_CREATE_FAILURE_BACKOFF_NANOS = 100ms`。
- `doCreateIdle()` 发生 duplicate、异常或 validate failed 返回 `null` 前记录短暂冷却。
- `insureMinIdle()`、`insureTargetSize()`、`triggerMinIdleMaintain()` 在冷却期内直接跳过补建。
- idle 创建成功后清除冷却。
- `borrow()` 的直接创建路径继续使用现有 `backoffCreateFailure()`，不要把 borrow 热路径和后台 idle 维护冷却强耦合。

### ThreadLocal hint

当前 L1 ThreadLocal 只是 hint，不再隐藏 idle 对象，功能正确性已通过测试覆盖。

剩余风险：

- Netty EventLoop 等长生命周期线程可能短期保留 retired `ObjectConf` 引用，直到下一次 borrow 清理或线程结束。
- 当前测试已覆盖 stale hint 不会被借出，后续压测可继续观察内存保留。

### 泄漏检测

默认 `closeObjectOnLeak=false` 是更安全的方向，避免后台扫描关闭仍被业务持有的 borrowed 对象。

如果显式开启 `closeObjectOnLeak=true`：

- 这是破坏性强制回收。
- RPC / Socks / UDP 调用方必须能承受连接、session 或租约被后台关闭。

## 可观测性要求

生产或压测环境建议至少监控：

- 堆外内存占用，特别是 Netty direct memory。
- 池总数：`rx.object_pool.size.count`。
- 空闲数：`rx.object_pool.idle.count`。
- 活跃数估算：`rx.object_pool.active.count`。
- 等待借用线程数：`rx.object_pool.waiting.count`。
- borrow timeout 次数：`rx.object_pool.borrow.timeout.count`。
- 创建与销毁次数：`created.count`、`retired.count`。
- 动态目标总容量：`rx.object_pool.target.total.count`。
- 连接数、吞吐、平均延迟、P99 / P999 延迟。
- 泄漏告警与 close-on-leak 次数。

## 后续建议

1. 对 RPC pool、Socks session pool、UDP lease pool 分别做长时间压测，观察 direct memory、连接数、timeout、retired 与 P99 延迟。
2. 将 Remoting pool 模式纳入稳定 CI 回归；如仍有冷启动 1s 时序抖动，应增加预热或放宽测试超时。
3. 将 `doRetire(wrapper, action)` 的 magic int 替换为 Java 8 兼容的 `static final int` 常量，降低后续日志和指标误分类风险。
4. 如生产日志中 create failure WARN 过密，再增加日志采样或按池名聚合告警。

## 结论

本轮 P1 问题已修复：duplicate idle 创建失败现在会触发 idle 维护路径冷却，不再在短时间内被 `validNow()`、`insureMinIdle()` 或异步维护任务反复补建。

当前验证结论：`ObjectPoolTest` 与 `Socks5SessionPoolTest` 均通过。下一阶段可以进入 RPC / Socks / UDP 真实场景压测与 CI 稳定性回归。
