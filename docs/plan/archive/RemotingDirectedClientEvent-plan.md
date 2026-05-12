# 背景

用户需求：在 `rockylomo/rxlib` 的 `master` 分支中扩展 Remoting，支持 client 调用一个 RPC 方法后，服务端方法内部执行较长任务，任务完成后只通知当初发起调用的 client，而不是广播给所有订阅者。

用户补充：希望评估是否需要把该扩展事件持久化到本地 H2，保证服务端重启后也能尽量推送结果。例如执行中重启后重新执行并推送，执行完重启后直接补推结果。

本计划只做 review 和设计文档提交，不修改业务代码。后续需用户明确“按计划执行/开始修改代码”后再进入实现阶段。

# 任务类型判断

本次属于新需求 + Review / 设计类任务。

原因：
- 新需求：需要新增 Remoting 定向通知能力。
- Review：用户要求顺带 review 当前 Remoting 相关实现及依赖类，并纳入计划。
- 持久化：属于可选增强，需要评估 H2/outbox 是否应进入实现范围，避免把基础定向通知和可靠任务恢复过度耦合。

# 当前上下文

已 review 文件：

- `rxlib/src/main/java/org/rx/net/rpc/Remoting.java`
- `rxlib/src/main/java/org/rx/net/rpc/RemotingContext.java`
- `rxlib/src/main/java/org/rx/net/rpc/RemotingEventArgs.java`
- `rxlib/src/main/java/org/rx/net/rpc/RemotingHybridOptions.java`
- `rxlib/src/main/java/org/rx/net/rpc/RpcClientConfig.java`
- `rxlib/src/main/java/org/rx/net/rpc/RpcServerConfig.java`
- `rxlib/src/main/java/org/rx/net/rpc/protocol/EventFlag.java`
- `rxlib/src/main/java/org/rx/net/rpc/protocol/EventMessage.java`
- `rxlib/src/main/java/org/rx/net/rpc/protocol/MetadataMessage.java`
- `rxlib/src/main/java/org/rx/net/rpc/protocol/MethodMessage.java`
- `rxlib/src/main/java/org/rx/net/transport/hybrid/HybridServer.java`
- `rxlib/src/main/java/org/rx/net/transport/hybrid/HybridSession.java`
- `rxlib/src/main/java/org/rx/net/transport/hybrid/DefaultHybridSession.java`
- `rxlib/src/main/java/org/rx/net/transport/hybrid/HybridSendOptions.java`
- `rxlib/src/test/java/org/rx/net/rpc/RemotingTest.java`
- `rxlib/src/test/java/org/rx/test/UserManager.java`
- `rxlib/src/test/java/org/rx/test/UserManagerImpl.java`
- `rxlib/src/test/java/org/rx/test/UserEventArgs.java`

关键调用链：

1. client 通过 `Remoting.createFacade` 创建动态代理。
2. 普通方法被封装为 `MethodMessage`，通过 `HybridSession.send(..., RemotingHybridOptions.METHOD)` 发送到服务端。
3. 服务端 `onServerReceive` 收到 `MethodMessage` 后进入 `invokeAndReply`。
4. `invokeAndReply` 通过 `RemotingContext.invoke(..., server, session)` 设置当前调用上下文。
5. 服务端反射调用 contract 实例方法。
6. 返回值或异常写回同一个 `MethodMessage`，发送给 client。

事件现状：

1. client 调用 `attachEvent` 时，facade 先在本地挂 handler，再发送 `EventMessage(eventName, SUBSCRIBE)` 到服务端。
2. 服务端按 eventName 维护 `ServerBean.EventBean.subscribe`，保存订阅的 `HybridSession`。
3. 服务端本地触发事件后，`doSendBroadcast` 遍历订阅 session 并发送 `EventMessage(eventName, BROADCAST)`。
4. client 收到 `BROADCAST` 后调用本地 `publishEvent` 执行 handler。

Review 结论：

- 当前 Remoting 事件模型是订阅 + 广播，不是请求上下文绑定 + 定向回调。
- `RemotingContext` 已能在服务端同步 RPC 方法内拿到当前 `HybridServer` 和 `HybridSession`，这是定向通知的最佳切入点。
- `HybridSession.send(packet, options)` 已有向单个 session 发送对象的底层能力。
- `HybridServer.getSession(long sessionId)` 和 `HybridSession.attr(...)` 可以支撑 session 查询与状态挂载。
- `RemotingContext` 是 `FastThreadLocal`，服务端方法切到异步线程后上下文会消失，因此必须在同步方法内先捕获 client handle。
- `HybridSession` 是连接态对象，服务端重启、client 重连或连接断开后会失效，不能作为持久化身份。
- 第一阶段不需要修改 `EventFlag` 或 `EventMessage` 结构；服务端选择单个 session 发送 `BROADCAST` 包即可完成定向通知。
- 持久化推送需要稳定 clientId、任务幂等和 outbox 语义，不能只靠当前 sessionId。

# 目标

1. 新增 Remoting 定向事件能力：服务端只向当初发起 RPC 调用的 client 推送事件。
2. 支持服务端方法启动异步长任务后，通过事先捕获的 client handle 完成定向通知。
3. 保持现有普通事件、计算事件、RPC 方法调用语义兼容。
4. 避免业务代码直接依赖 `EventMessage`、`HybridSession.send`、`RemotingHybridOptions` 等内部协议细节。
5. 评估并设计可选 H2/outbox 持久化方案，覆盖服务端重启后的补推和重跑语义。
6. 增加测试覆盖定向通知、断连、非广播、可选持久化边界。

# 非目标

1. 第一阶段不改造计算事件。
2. 第一阶段不改变 `publishEvent` 的广播语义。
3. 第一阶段不要求 `poolMode` 支持长连接事件订阅或定向回调。
4. 第一阶段不强制引入 H2 依赖到 Remoting 核心。
5. 不实现通用分布式任务调度系统。
6. 不保证任意业务任务自动幂等；重启后重新执行需要业务 taskId 和 executor 支持幂等/重放。
7. 不修改 secrets、token、证书、私钥，不自动发布 release。

# 设计方案

## 阶段一：内存态定向通知

新增轻量 handle：

```java
public final class RemotingClientHandle {
    private final transient HybridServer server;
    private final long sessionId;
    private final String peerId;
    private final InetSocketAddress tcpRemoteEndpoint;
}
```

新增 API：

```java
public static RemotingClientHandle currentClientHandle();

public static <TEvent extends EventArgs> boolean publishEventToClient(
        RemotingClientHandle client,
        String eventName,
        TEvent eventArgs);

public static <TEvent extends EventArgs> boolean publishEventToCurrentClient(
        String eventName,
        TEvent eventArgs);
```

使用方式：

```java
public String startLongTask(TaskRequest request) {
    String taskId = request.getTaskId();
    RemotingClientHandle client = Remoting.currentClientHandle();

    Tasks.run(() -> {
        TaskResult result = doLongWork(taskId, request);
        Remoting.publishEventToClient(client, "taskCompleted",
                new TaskCompletedEventArgs(taskId, result));
    });

    return taskId;
}
```

内部实现：

1. `currentClientHandle()` 从 `RemotingContext.context()` 获取当前 server/session。
2. 捕获 sessionId、peerId、remote endpoint，并保留当前 JVM 内有效的 transient server 引用。
3. `publishEventToClient` 通过 server + sessionId 重新查找当前 session。
4. 校验 session 仍 connected，peerId/endpoint 匹配。
5. 构造 `EventMessage(eventName, EventFlag.BROADCAST)`，填充 `eventArgs`。
6. 调用 `session.send(message, RemotingHybridOptions.event(message))`。
7. 返回 true/false 表示是否发送成功。

注意：client 侧仍需本地 `attachEvent("taskCompleted", handler, false)`，否则收到 `BROADCAST` 后本地没有 handler，等价 no-op。

## 阶段二：可选 H2 / outbox 持久化

是否需要 H2：

- 如果只需要在线连接期间的定向通知，不需要 H2。
- 如果需要服务端重启后补推结果，需要持久化 outbox。
- 如果需要执行中重启后重新执行，需要持久化 task request，并要求业务任务幂等。

不建议 Remoting 核心直接 hard-code H2。建议增加可插拔接口：

```java
public interface RemotingOutboxStore {
    void save(RemotingOutboxMessage message);
    List<RemotingOutboxMessage> loadPending(int limit);
    void markDelivered(String id);
    void markFailed(String id, String error, long nextRetryAt);
}
```

默认实现：

- `NoopRemotingOutboxStore` 或 `InMemoryRemotingOutboxStore`。

可选实现：

- `H2RemotingOutboxStore`，仅在确认允许新增/复用 H2 依赖后实现。

持久化模型建议字段：

- `taskId`：业务幂等任务 id。
- `clientId`：稳定 client 身份，不能用 sessionId。
- `eventName`。
- `eventArgsBytes` 或 `eventArgsJson`。
- `status`：PENDING / RUNNING / COMPLETED / DELIVERING / DELIVERED / FAILED。
- `attempts`。
- `nextRetryAt`。
- `createdAt` / `updatedAt`。
- `lastError`。

两种恢复语义：

1. 执行完重启后补推：任务完成后先写 outbox，再发送；发送成功标记 delivered；发送前重启则重启后扫描 pending 并补推。
2. 执行中重启后重新执行：方法入口先持久化 task request；重启后扫描 RUNNING/PENDING_EXECUTION 的任务重新提交 executor；完成后写 outbox 并推送。

稳定 client 身份：

第一阶段不建议修改握手。第二阶段若要通用可靠推送，建议在 `RpcClientConfig` 增加可选 `clientId`，通过握手消息传到服务端并保存在 session attr 中。更小范围替代方案是由业务方法显式传入 clientId。

推荐实施顺序：

1. 先实现阶段一定向通知，不引入 H2。
2. 再根据实际“可靠投递”要求，决定是否实现 `RemotingOutboxStore` + H2 可选实现。
3. 如果要支持执行中重启恢复，必须同时定义业务 task executor 注册、幂等 taskId、request/result 序列化规则。

# 修改文件列表

第一阶段预计修改：

- `rxlib/src/main/java/org/rx/net/rpc/Remoting.java`
- `rxlib/src/main/java/org/rx/net/rpc/RemotingClientHandle.java`
- `rxlib/src/test/java/org/rx/net/rpc/RemotingTest.java`
- `rxlib/src/test/java/org/rx/test/UserManager.java` 或新增专用测试 contract
- `rxlib/src/test/java/org/rx/test/UserManagerImpl.java` 或新增专用测试实现
- `rxlib/src/test/java/org/rx/test/TaskCompletedEventArgs.java`（如测试需要）

第二阶段可选修改：

- `rxlib/src/main/java/org/rx/net/rpc/RemotingOutboxStore.java`
- `rxlib/src/main/java/org/rx/net/rpc/RemotingOutboxMessage.java`
- `rxlib/src/main/java/org/rx/net/rpc/InMemoryRemotingOutboxStore.java`
- `rxlib/src/main/java/org/rx/net/rpc/H2RemotingOutboxStore.java`（仅确认允许 H2 后）
- `rxlib/src/main/java/org/rx/net/rpc/RpcServerConfig.java`
- `rxlib/src/main/java/org/rx/net/rpc/RpcClientConfig.java`（如增加 clientId）
- `rxlib/src/test/java/org/rx/net/rpc/RemotingReliableDirectedEventTest.java`

# 风险点

兼容性风险：

- 新 API 名称若不清楚，可能与现有广播 `publishEvent` 混淆。
- 修改协议字段会带来序列化兼容风险，因此第一阶段避免修改 `EventMessage` 结构。
- 如果暴露 `HybridSession` 给业务，会破坏 transport 封装。

性能风险：

- 定向发送只发一个 session，性能风险低。
- outbox 扫描和重试可能造成数据库频繁写入，需要 limit、nextRetryAt、退避策略。
- 大型 result 持久化到 H2 会增加磁盘与序列化压力，应限制 payload 或只存业务结果引用。

并发风险：

- 异步任务完成时原始 session 可能已断开。
- client 重连后 sessionId 变化，第一阶段 handle 无法自动追踪新 session。
- outbox 至少一次投递可能导致重复通知，client handler 必须按 taskId 去重。

资源释放风险：

- handle 不应持有需要关闭的资源。
- outbox store 生命周期需明确：若 Remoting 创建则随 server close 关闭；若外部注入则由外部管理。

语义风险：

- “保证推送”不能解释为绝对成功。网络断连、client 永久不上线、H2 文件损坏等场景无法完全保证。
- 更合理语义是“至少一次投递 / best-effort retry”。
- 执行中重启后重新执行必须由业务任务提供幂等或补偿能力。

# 验证方案

第一阶段测试：

1. `directedEvent_shouldNotifyOnlyCallingClient`
   - 启动服务端，创建两个 stateful facade。
   - 两个 client 都 attach 同名 `taskCompleted` handler。
   - client1 调用长任务方法。
   - 断言只有 client1 收到通知，client2 未收到。

2. `directedEvent_shouldReturnFalseWhenOriginalClientDisconnected`
   - client 调用后立即 close。
   - 服务端任务完成后 `publishEventToClient` 返回 false，不抛未捕获异常。

3. `publishEventToCurrentClient_shouldWorkInsideRpcThread`
   - 服务端同步方法内部直接调用 `publishEventToCurrentClient`。
   - client 收到本地事件。

4. `currentClientHandle_shouldFailOutsideServerRpcContext`
   - 非服务端 RPC 线程调用，按最终 API 断言抛 `InvalidException` 或返回 null。

第二阶段可选测试：

1. `outbox_shouldPersistCompletedResultBeforeDelivery`
   - 任务完成写 outbox，模拟发送失败，重启 store 后仍可读取 pending。

2. `outbox_shouldDeliverPendingResultAfterClientReconnect`
   - client 使用稳定 clientId，服务端 pending outbox 存在，client reconnect 后补推并标记 delivered。

3. `taskRecovery_shouldResubmitRunningTaskAfterRestart`
   - 使用幂等 taskId 和测试 executor，模拟 RUNNING 状态重启后重新执行并产生结果。

GitHub Actions：

- 代码实现阶段后触发 `jdk8-unit-tests.yml`。
- `test_classes` 至少包含 `org.rx.net.rpc.RemotingTest`，如新增独立测试类则包含 `org.rx.net.rpc.RemotingReliableDirectedEventTest`。
- 查询 workflow run 必须按当前分支过滤。
- 只有 `conclusion=success` 才认为 CI 通过。
