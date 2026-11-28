# 基础传输模块 (org.rx.net.transport & org.rx.net.transport.core)

提供 Netty TCP/UDP 基础封装与共享传输内核（Transport Core），直接以原始 `ByteBuf` 为一等数据类型，不依赖 RPC、Fury、SOCKS5 或 DNS。

## 共享传输内核 (org.rx.net.transport.core)

- **`TransportConfig`**:
  可靠字节流客户端/服务端统一配置，包含内嵌 `TcpClientConfig`/`TcpServerConfig`、`enableAdaptiveUdp`（默认 `true`，复用 RSS `daily` 单端口自适应 UDP 加速；设为 `false` 时纯 TCP 且零 UDP Socket）、`udpBindPort`、`udpMtu`、`authSecret`、`enableEncryption`（握手后每会话独立 AES-128-GCM 认证加密）、`enableCompression`（业务级 `zlib 3` 流压缩，严格位于加密内层：先压缩后加密）、`egressConfig`（统一公共 `EgressCodec` 出口包装：`NONE` / `ZLIB_STORED` / `HTTP` / Server `AUTO`）、`handshakeTimeoutMillis`、`maxFrameBytes`。

- **`TransportServer` / `TransportClient` / `TransportSession`**:
  原始 `ByteBuf` 可靠有序字节流服务端、客户端与会话抽象：
  - 基于 `RXTC` **v2** 二进制握手完成会话 ID、能力位（`FLAG_ADAPTIVE_UDP`、`FLAG_AUTH`、`FLAG_ENCRYPT`、`FLAG_COMPRESS`）与 MTU 协商，通过 `FRAME_READY` 屏障避免 TCP 粘包与加解密/`AdaptiveTcpHandler` 切入竞争。
  - 当配置非空 `authSecret` 时，握手期强制启用 `FLAG_AUTH` 双向 16 字节 HMAC-SHA256 认证（`Hello` 携带 `clientNonce + clientMac`，服务端通过有界 TTL 缓存防御 `clientNonce` 重放；`HelloAck` 携带 `serverNonce + serverMac`），在创建会话或进入任何 Fury 反序列化前验证，不通过立即断开。
  - 启用 `enableEncryption`（要求非空 `authSecret`）时，握手成功后立即为每会话/每方向派生独立 AES-128-GCM 密钥并安装 `TransportSessionCipher`（与可选 `zlib 3` 压缩配合，业务压缩在内、AEAD 加密居中、`EgressCodec` 出口包装位于最外层）。
  - **虚拟载体支持（`LocalAddress` / `LocalChannel`）**：`TransportServer.startLocal(LocalAddress)`、`TransportClient.connectLocal(LocalAddress)` 与 `Remoting`（`RpcServerConfig.localCarrier` / `RpcClientConfig.localCarrier`）支持在零额外物理 Socket 下将 RRP RAW 中继逻辑流直接桥接为完整的端到端加密 Transport / RPC 会话，并支持 `requestDeferredAdaptive(...)` 延迟挂接外部已验证 UDP 路径。
  - `enableAdaptiveUdp = false` 时纯 TCP，不绑定、不创建任何 UDP Socket（`TransportServer.udpPort() == 0`）。
  - `enableAdaptiveUdp = true` 时在单端口上复用 RSS `daily`（`AdaptiveTcpHandler` + `AdaptiveUdpPort`，不启用端口跳跃与包复制），提供单一层的 FEC + TCP 可靠修复；支持动态 PMTU 探测与 `CHUNK` 收缩，以及通过 `attachVerifiedUdpPath(...)` 接入 `PeerUdpPuncher` 验证后的直连/打洞 UDP 路径。
  - 会话提供 `send(ByteBuf)` / `sendAsync(ByteBuf)`、`attr(...)`、`isEncrypted()`、`isAdaptiveUdpActive()`、`isUsingUdp()` 与清晰的所有权/释放语义。

- **`DatagramTransport` / `DatagramTransportConfig`**:
  原始 `ByteBuf` 无连接 UDP 传输组件，默认单端口绑定（`reusePortBindCount = 1`），支持直发/直收原始 datagram，也可通过 `installDatagramHandler(RssDatagramHandler)` 在 EventLoop 上同步挂载认证分片与 FEC 保护。

## 基础 I/O 组件 (org.rx.net.transport)

- **`TcpServer`**:
  基础 TCP 服务器封装，支持端口监听、SSL 与连接/报文回调。

- **`TcpClient` / `AbstractTcpReconnectClient`**:
  基础 TCP 客户端与通用断线重连基类（`TransportClient` 亦继承自 `AbstractTcpReconnectClient`）。

- **`SftpClient`**:
  基于 SFTP 协议的文件传输客户端。

- **已退役删除**：
  旧版对象/消息 `org.rx.net.transport.UdpClient`、`UdpClientConfig`、`UdpServerHandler`、`FuryUdpClientCodec`、`Hybrid*` 及 `org.rx.net.udp.UdpHolePunch*` 已彻底删除（2026-11-28）。RPC 与 `NameserverImpl` 全部运行在 `org.rx.net.transport.core` 之上；原生 UDP 数据报请使用 `DatagramTransport`。

## 适用场景
- 原始二进制协议/自定义字节流服务（直接使用 `TransportServer` / `TransportClient`，无需构造 RPC facade）。
- 独立 UDP 数据报通信（直接使用 `DatagramTransport`）。
- 作为 RPC、Nameserver 副本同步与 RSS 共用的可靠传输内核。
