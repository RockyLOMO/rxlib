# 服务发现模块 (org.rx.net.nameserver)

基于 Netty 实现的轻量级内部命名服务（Name Server），类似于微缩版的 Nacos 或 Eureka。

## 核心类介绍

- **`Nameserver`**:
  命名服务器的服务端入口。主要负责维护服务列表及各个节点的健康状态。

- **`NameserverImpl`**:
  服务端的核心逻辑实现类，包括服务注册、心跳保活检测、路由列表下发等功能。

- **`NameserverClient`**:
  客户端。默认每 120 秒上报心跳。注册后把 DNS 端口注入 `Sockets.injectNameService`。

副本同步按 `sourceId` 拒绝更旧的版本。实例键是 `appName#ip`。全量同步周期默认 60 秒（`replicaFullSyncPeriodMillis`）。没有持久化 Outbox。

## 适用场景
- 没有引入外部中间件（如 Nacos、Zookeeper）时的集群内 RPC 服务寻址与动态负载均衡。
