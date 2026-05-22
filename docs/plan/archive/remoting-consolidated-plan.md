# Remoting 统一升级与优化综合计划

本文档整合了 `RemotingHybridMigration`、`RemotingDnsNameserverReviewFix` 以及 `RemotingDirectedClientEvent` 三个计划的背景、执行状态和后续设计，作为 Remoting 及底层网络基础设施演进的全局视角。

## 1. 整体背景与模式

- **项目定位**：高性能模式（Netty 底层网络编程）。
- **核心目标**：统一 RPC 传输层底座（Hybrid）、修复已知并发与性能隐患（DNS/Nameserver/Remoting）、以及扩展面向长连接的精细化定向通知能力。
- **总体进度**：Hybrid 迁移和基础修复已完成并落地，定向事件与可靠补推作为下一步重要功能扩展等待实施。

---

## 2. 第一部分：Remoting Hybrid 迁移（已完成）

### 2.1 目标与结论
将 Remoting 的通信底座从 `DefaultTcpClient` / `TcpServer` 平滑迁移到 `HybridClient` / `HybridServer`，统一 RPC 方法调用、事件订阅和事件广播在 `HybridSession` 上的承载。

### 2.2 落地现状
- `Remoting` 客户端/服务端已全面切换到 Hybrid 相关组件，并保留了原有的兼容访问方式。
- 新增 `RpcHybridClientPool` 等连接池化实现，重构了 `RemotingContext` 的上下文获取。
- 采用保守的发布策略：控制消息、方法调用、方法响应、事件广播默认全部走 `FORCE_TCP`。UDP 快路径作为储备功能在稳定基线之上逐步评估放开。

---

## 3. 第二部分：Remoting / DNS / Nameserver 修复与复查（已完成）

### 3.1 Remoting 修复
- **事件订阅去重**：解决了 `SUBSCRIBE` 重复 `attachEvent` 导致的潜在监听器泄漏。
- **线程模型**：增加了可选的业务线程池 offload 能力，避免服务端同步反射执行阻塞 I/O 线程。
- **连接池语义保护**：显式拒绝 `poolMode` 下的长连接事件订阅操作。

### 3.2 DNS / DoH 修复
- **缓存隔离**：A/AAAA 查询 coalescing key 和 interceptor cache 已按地址族严格隔离，避免 IPv4/IPv6 语义串扰。
- **低分配优化**：`normalizeDomain` 重构为 ASCII 扫描的保守低分配实现。
- **异常保护**：修复了 DoH response body 编码异常路径可能导致泄漏的问题。

### 3.3 Nameserver 修复
- **版本校验优化**：引入 `replicaVersion` 并按 source-aware 维护最后应用版本，避免旧包覆盖新包。
- **包结构调整**：引入 `ReplicaSnapshot` / `ReplicaFullSync` DTO，避免 UDP 同步发送 live concurrent map/set。
- **健康检查清理**：修复了 client `healthTask` 未正确取消的问题。

---

## 4. 第三部分：Remoting 定向事件扩展设计（待实施）

### 4.1 业务需求
当前 Remoting 事件为“订阅-广播”模型，需扩展支持服务端执行长任务后，将结果仅推送给当初发起 RPC 调用的特定 client，避免广播给所有订阅者。同时需考虑异常断连/服务端重启下的持久化补推可能。

### 4.2 核心设计方案
**阶段一：内存态定向通知**
- 引入轻量级句柄：捕获当前的 `server` 引用及 `sessionId`，并验证对端特征：
  ```java
  public final class RemotingClientHandle {
      private final transient HybridServer server;
      private final long sessionId;
      private final String peerId;
      private final InetSocketAddress tcpRemoteEndpoint;
  }
  ```
- 新增服务端定向推送 API，在原始会话仍存活时直接路由单播 `BROADCAST` 报文：
  ```java
  public static RemotingClientHandle currentClientHandle();
  public static <TEvent extends EventArgs> boolean publishEventToClient(RemotingClientHandle client, String eventName, TEvent eventArgs);
  ```

**阶段二：可选的持久化补推 (Outbox)**
- 方案：引入 `RemotingOutboxStore` 可插拔接口，隔离核心网络和本地 H2 依赖。
- 如果需要服务端重启后补推结果，可开启基于 outbox 的重试；这要求 `RpcClientConfig` 支持配置固定的 `clientId` 以用于断线重连后的身份识别。

---

## 5. 后续待办与未决事项汇总

1. **Remoting Hybrid UDP 放开**：
   - 评估并择机为较小的 `MethodMessage` 和 `EventMessage` 开启 UDP 路由与降级策略，同时需补充 “UDP ACK timeout 后补发且业务仅投递一次” 的专项测试。
2. **DNS 空地址族语义确认**：
   - 当前在拦截器空返回时采用了 NXDOMAIN。后续需确认在严格语义下，是否应该针对“域名存在但无对应记录（如 IPv4 only 查 AAAA）”返回 NOERROR / NODATA。
3. **Nameserver UDP 大包策略**：
   - 针对 `ReplicaFullSync` 大包问题，需增加编码字节数监控；若用于跨机房大规模注册，考虑将 full sync 演进为 RPC/TCP 拉取。
4. **DNS 缓存隔离演进**：
   - `DnsResolveCore` 中 interceptor 缓存尚未按 `srcIp` 隔离，该项可能影响分流结果，已明确作为后续独立需求计划（`DnsInterceptorSourceAwareCache-plan.md`）评估。
