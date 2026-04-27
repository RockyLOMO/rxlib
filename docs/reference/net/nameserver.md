# 服务发现模块 (org.rx.net.nameserver)

基于 Netty 实现的轻量级内部命名服务（Name Server），类似于微缩版的 Nacos 或 Eureka。

## 核心类介绍

- **`Nameserver`**:
  命名服务器的服务端入口。主要负责维护服务列表及各个节点的健康状态。

- **`NameserverImpl`**:
  服务端的核心逻辑实现类，包括服务注册、心跳保活检测、路由列表下发等功能。

- **`NameserverClient`**:
  客户端。通常内嵌在 RPC 或其他服务的底层，定时向 Nameserver 报告健康状态，并长轮询或监听目标服务的可用节点变化。

## 适用场景
- 没有引入外部中间件（如 Nacos、Zookeeper）时的集群内 RPC 服务寻址与动态负载均衡。
