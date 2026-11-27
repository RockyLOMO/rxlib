# 服务发现模块 (org.rx.net.nameserver)

基于 Netty 与共享 Transport Core 实现的轻量级内部命名服务（Name Server），类似于微缩版的 Nacos 或 Eureka。

## 核心类介绍

- **`Nameserver`**:
  命名服务器的服务端契约接口，负责服务实例注册、心跳保活与路由发现。

- **`NameserverImpl`**:
  服务端核心实现：
  - 注册与发现服务入口通过 `Remoting.register(...)` 暴露在 `TransportServer` 上，会话属性直接绑定在 `TransportSession` 上，连接断开前自动注销实例。
  - 副本同步通过共享 `DatagramTransport`（固定单端口绑定 `reusePortBindCount = 1`）+ `FuryUdpClientCodec` 实现，同步回调统一调度到后台线程池执行，不阻塞 Netty EventLoop。

- **`NameserverClient`**:
  客户端。默认每 120 秒上报心跳。注册后把 DNS 端口注入 `Sockets.injectNameService`。

副本同步按 `sourceId` 拒绝更旧的版本。实例键是 `appName#ip`。全量同步周期默认 60 秒（`replicaFullSyncPeriodMillis`）。没有持久化 Outbox。

## 适用场景
- 没有引入外部中间件（如 Nacos、Zookeeper）时的集群内 RPC 服务寻址与动态负载均衡。
