# RPC 模块 (org.rx.net.rpc)

rxlib 自研的高性能远程过程调用框架。支持 TCP 单一协议，也支持 TCP/UDP 混合传输（Hybrid），通过内置的序列化器（如 Fury）实现极致的序列化性能。

## 核心类介绍

- **`Remoting`**:
  RPC 模块的顶级外观类，提供了注册服务端实现和创建客户端代理的核心工厂方法。

- **`RpcServerConfig` / `RpcClientConfig`**:
  RPC 服务端与客户端的详细配置类。包含连接超时、池大小、通信加密、混合传输支持等相关设置。

- **`RpcClientPool`**:
  用于缓存客户端长连接。避免每次 RPC 调用都建立新连接。

- **`RpcHybridClientPool` / `RpcHybridClientPoolImpl`**:
  针对混合传输协议的特殊客户端连接池。它可以在 TCP 控制流的基础上，选择将适合的流量切入 UDP 加速通道。

- **`RemotingCodecFactory` / `FuryRemotingCodecFactory`**:
  序列化编解码器工厂。推荐使用基于 `Fury` 的编解码，实现低延迟与高吞吐的零拷贝序列化。

- **`RemotingContext`**:
  RPC 调用上下文，在一次 RPC 调用的全生命周期中存储 Trace ID 及扩展参数，实现链路追踪与隐式参数传递。

## 适用场景
- 内部系统间的低延迟、高频调用通信。
- 对传输性能有严苛要求的私有协议服务（相比 HTTP REST 或 gRPC 更轻量和迅速）。
