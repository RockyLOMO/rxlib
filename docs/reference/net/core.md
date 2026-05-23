# rxlib 核心网络模块 (org.rx.net)

本包为 `rxlib` 提供高性能底层网络编程的基石，主要基于 Netty 进行抽象和增强。包含了全局网络配置、底层 Sockets 封装以及处理背压、编解码等基础处理器。

## 核心类介绍

- **`Sockets`**:
  底层 Socket 操作和网络参数提取的核心工具类。提供了端口检查、IP 解析、Netty `EventLoopGroup` 分配、快速获取本地/远程地址等静态工具方法。

- **`TransportFlags`**:
  网络传输特征的标识枚举（如是否开启加密、压缩、混合传输等）。在 RPC 与代理模块中广泛用于协调客户端与服务端的协商特性。

- **`TcpBackpressureHandler`**:
   TCP relay 管道中的背压控制器，通过监听 outbound 通道的 `channelWritabilityChanged` 事件，在写水位（Write Buffer Watermark）过高时自动挂起 inbound 读取操作，防止 OOM。这是高性能模式下内存泄漏防护的关键。

- **`GlobalChannelHandler`**:
  全局 Channel 处理器，通常用于处理未捕获的异常、拦截连接生命周期并对接指标监控框架。

- **`org.rx.net.udp.UdpProtect` / `UdpProtectEncoder` / `UdpProtectDecoder`**:
  可插拔 UDP protect pipeline，提供轻量 XOR FEC、按 peer/session 分组、变长 payload 恢复和可选多倍发包，适用于自有代理公网链路的游戏 UDP 抗丢包优化。

- **`CipherEncoder` / `CipherDecoder`**:
  基础的数据加密/解密处理器，用于轻量级自定义协议中的对称加密。

## 适用场景
为整个 `org.rx.net` 生态提供基础设施。如果您需要建立新的基于 Netty 的 Server/Client，或者是优化现有的连接管理和协议解析，这里是不可缺少的底层基石。
