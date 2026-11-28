# 服务发现模块 (org.rx.net.nameserver)

基于 Netty 与共享 Transport Core 实现的轻量级内部命名服务（Name Server），类似于微缩版的 Nacos 或 Eureka。

## 核心类介绍

- **`Nameserver`**:
  命名服务器的服务端契约接口，负责服务实例注册、心跳保活、路由发现以及已鉴权副本增量/分页快照同步（`syncDelta`、`syncSnapshotPage`）。

- **`NameserverReplicaProtocol`**:
  副本同步协议模型，定义稳定实例身份、`(epoch, version)` 单调版本、墓碑（tombstone）与分页快照载荷，并对每条副本消息执行 HMAC-SHA256 签名与防伪校验。

- **`NameserverImpl`**:
  服务端核心实现：
  - 注册与发现服务入口通过 `Remoting.register(...)` 暴露在 `TransportServer` 上，会话属性直接绑定在 `TransportSession` 上，连接断开前自动注销实例。
  - **可选 `NodeRegistry` 权威源集成**：当注入已启用的 `NodeRegistry` 时，`NameserverImpl` 将本机注册实例同步托管至 `NodeRegistry` 服务表与在线索引，远端副本实例按租约 TTL 自动过期清理，并在节点被禁用/凭据轮换吊销时联动剔除会话，与未启用 `NodeRegistry` 的默认纯内存模式完全解耦。
  - **副本同步已全面迁移至 Transport Core RPC（默认 `daily` 自适应双轨模式）**：不再使用旧版 UDP `syncPort` / `DatagramTransport` 同步；Peer 之间复用 `registerPort` 上的已鉴权 RPC 通道，并支持 `replicaSecret` HMAC 鉴权与 `replicaPeers` 白名单校验。
  - **状态一致性与内存边界**：实例身份使用 `instanceId`（而非仅 `appName#ip`），支持单调 `(epoch, version)` 防旧覆盖、显式墓碑删除避免重启/重连死而复生、按页全量对账（`syncSnapshotPage`）避免单包溢出；同步回调统一调度到后台线程池执行，不阻塞 Netty EventLoop。

- **`NameserverClient`**:
  客户端。默认每 120 秒上报心跳，携带稳定 `instanceId`。注册后把 DNS 端口注入 `Sockets.injectNameService`。

- **配置变更**：`NameserverConfig` 已移除旧 `syncPort`；副本同步默认全量对账周期为 60 秒（`replicaFullSyncPeriodMillis`），支持配置 `replicaSecret` 与 `replicaPeers`（对等节点 `host:registerPort` 列表）。无持久化 Outbox。

## 适用场景
- 没有引入外部中间件（如 Nacos、Zookeeper）时的集群内 RPC 服务寻址、动态负载均衡与多节点轻量化注册表同步。
