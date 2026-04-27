# 代理与穿透模块 (org.rx.net.socks)

此模块提供全面的 SOCKS5、Shadowsocks 以及基于 Rx 自定义的 RRP 代理与中继服务协议支持。包含代理的服务端和客户端，并实现了高级别特性的 UDP 转发与穿透能力。

## 核心类介绍

- **`SocksProxyServer`**:
  完整的 SOCKS5 代理服务器。它不仅支持标准的 TCP 代理，还完美兼容 Full Clone NAT 下的 UDP 代理机制。通过对接各类处理器（如 `Socks5WarmupHandler`，`SocksUdpRelayHandler`），支持动态上游切换。

- **`Socks5Client`**:
  代理客户端实现。专门优化了 SOCKS5 UDP 的连接建立流程（实现了 `Socks5UdpSession` 池化机制），消除了反复握手建立 UDP 会话的性能开销。

- **`ShadowsocksServer` / `SSUdpProxyHandler` / `SSTcpProxyHandler`**:
  实现了 Shadowsocks 兼容协议的服务端，支持多种加密密码学配置。

- **`RrpServer` / `RrpClient`**:
  “Rx Remoting Proxy” 的实现，专为长连接或混合连接的远程反向代理、内网穿透设计的服务端与客户端套件。

- **`Udp2rawHandler`**:
  提供了将 UDP 流量伪装为 TCP 流量的功能，以穿过某些阻断 UDP 的严苛防火墙策略。

- **`UdpCompressCodec` / `UdpRedundantCodec`**:
  为 UDP 代理数据在弱网环境下提供的额外增强：压缩（减少带宽占用）与冗余（多倍发送减少丢包率影响）。

## 适用场景
- 开发商业级的科学上网工具或企业级内网穿透/异地组网系统。
- 为某些仅提供代理端口的服务增加协议适配层。
