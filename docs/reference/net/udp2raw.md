# Udp2raw

## 简介

`Udp2raw` 把 SOCKS/SS 的 UDP payload 包进自有 UDP 帧，经 `Udp2rawUpstream` 发到对端再解出。帧由 `Udp2rawCodec` 编解码，是定长头的 datagram，不是字节流。`SocksUdpRelayHandler` 仍然存在，udp2raw 不替换它。

## 特性

- **客户端 / 服务端**：`SocksConfig.enableUdp2raw`。客户端再设置 `udp2rawClient`。默认要求 RPC 协商（`udp2rawRequireRpc=true`），认证默认 `FIRST_PACKET_MAC`。
- **冗余**：`Udp2rawPayloadSupport.newAdaptiveStats` 创建 `UdpRedundantStats`，按丢包率调整倍率，周期 2 秒。没有 `AdaptiveStats` 这个类。方向默认双向。
- **能力协商**：只有压缩、冗余等已有能力。没有 FEC，也不会自动装上 `UdpResilience`。
- **会话**：`Udp2rawAuthenticator`、`Udp2rawSessionKey`、`Udp2rawSeqWindow`。错误认证有熔断，peer 有速率上限。MTU 超限进入 `Udp2rawMtuState`。

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

## 进阶

- 冗余统计对象是 `UdpRedundantStats`，由 `newAdaptiveStats` 创建。
- `Udp2rawServerEntryManager` 是服务端入口的内部类，不是单独的公开配置 API。
- `Udp2rawSeqWindow` 做序号抗重放。空闲会话按 `udp2rawSessionIdleSeconds`（默认 300）回收，`udp2rawMaxSessions` 默认 65536。
