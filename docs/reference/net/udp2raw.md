# Udp2raw (UDP over Tunnel)

## 简介
`Udp2raw` 是 `rxlib` 网络模块中提供的一种将 UDP 流量伪装、封装和多路复用的隧道实现。
在常规的 SOCKS5 代理中，`UDP_ASSOCIATE` 依赖于原生的 UDP 传输。然而在很多弱网环境或受限网络中，原生 UDP 会遭遇 QoS 限速、丢包，甚至被完全阻断。`Udp2raw` 机制通过将 UDP 数据包封装在自定义的隧道流中，以提升穿透率、降低丢包率并提高通信稳定性。

## 特性
- **客户端/服务端模式**：通过简单的配置项即可在 Proxy 节点上启用 Client 模式或 Server 模式。
- **数据冗余（Redundancy）与自适应**：结合 `Udp2rawPayloadSupport` 和自适应状态统计（`AdaptiveStats`），支持对丢包率高的网络进行动态包冗余，从而在不增加明显重传延迟的情况下提升交付率。
- **与 SOCKS5 深度集成**：无缝替换原生的 `SocksUdpRelayHandler`。只要配置开启，上层应用的 SOCKS5 UDP 请求会自动被路由进 udp2raw 隧道，对客户端 App 完全透明。
- **安全与认证**：内置 `Udp2rawAuthenticator` 及 Session 机制（`Udp2rawSessionKey` / `Udp2rawSession`），防止未授权访问和探测攻击。

## 使用方式

`Udp2raw` 的核心配置集成在 `SocksConfig` 中，通常配合 `SocksProxyServer` 一同使用。

### 1. 服务端配置 (Server Mode)
服务端模式用于接收来自 udp2raw 客户端的封装数据，并将其解包后转发给真实的 UDP 目标。

要开启服务端模式，只需设置 `enableUdp2raw = true` 且不指定客户端上游。

```java
SocksConfig serverCfg = new SocksConfig();
serverCfg.setListenPort(1080);

// 开启 udp2raw 服务端模式
serverCfg.setEnableUdp2raw(true); 
// 不需要设置 udp2rawClient，保持默认（null）即可

SocksProxyServer serverProxy = new SocksProxyServer(serverCfg);
```

### 2. 客户端配置 (Client Mode)
客户端模式用于接收本地 App 的原生 SOCKS5 UDP 数据，将其封装为 udp2raw 协议，然后发往远端的 udp2raw 服务端。

要开启客户端模式，需要同时设置 `enableUdp2raw = true`，并指定 `udp2rawClient` 指向远端服务器的地址。

```java
SocksConfig clientCfg = new SocksConfig();
clientCfg.setListenPort(1081);

// 开启 udp2raw
clientCfg.setEnableUdp2raw(true);
// 指定远端 udp2raw 服务端的 UDP relay 地址和端口
InetSocketAddress udp2rawServerAddr = new InetSocketAddress("server_ip", 1080);
clientCfg.setUdp2rawClient(udp2rawServerAddr);

SocksProxyServer clientProxy = new SocksProxyServer(clientCfg);
```

### 3. 数据流向说明
当以如上方式配置后，完整的 SOCKS5 UDP 代理数据流向如下：

```text
App (Native UDP) 
  -> [SOCKS5 Client Proxy] 
  -> (Udp2raw Encapsulated Protocol) 
  -> [SOCKS5 Server Proxy] 
  -> (Native UDP) 
  -> Destination
```

1. **客户端 App** 发起 SOCKS5 `UDP_ASSOCIATE`。
2. **客户端 Proxy**（启动了 udp2raw Client 模式，`Udp2rawHandler` 接管）捕获 UDP 数据包，通过编解码器进行封装。
3. 封装后的流量穿越公网/受限网络到达 **服务端 Proxy**。
4. **服务端 Proxy**（启动了 udp2raw Server 模式）接收数据，解包验证并创建真正的 UDP Relay 发往目标地址，返回时同理封装。

## 进阶与调优
- **冗余参数**：对于丢包极高的环境，可利用 `UdpRedundantStats` 开启的冗余机制，依据 `effectiveMultiplier` 动态增加发包数量来抵抗丢包。
- **固定入口**：在大型部署中，可以结合 `Udp2rawServerEntryManager` 设置固定的服务端监听入口，统一管理隧道的生命周期。
- **Session 生命周期**：依靠 `Udp2rawSeqWindow` 维持序列号抗重放，当心跳超时或断线时自动清理和重建。
