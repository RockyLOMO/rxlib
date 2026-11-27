# rxlib 未来规划与待实现计划（Roadmap）

> 本文档仅记录**尚未实现、待验证或未来规划**的演进事项与设计思路。已完成项已移除，历史对照数据见 [测试与基准记录](test-benchmarks.md)。

---

## P1 · 近期优先（调优、真机验收与运维切换）

### 1. `weak` 模式高丢包单流大下载 FEC 调优
- **现象**：2026-11-27 在 `182.255.85.247`（10%–15% 丢包 / 182 ms RTT）实测中，`weak` 交互 P95 显著优于 `plain`/`daily`，但热 512 KiB 单流下载在突发窗口回落前 `AdaptiveUdpFec` 产生 `fecParityBytes = 0`，导致 TCP 补传、总流量多出 `+20%–29%`。
- **思路**：排查 `weak` 大流下 FEC 触发阈值与突发回落窗口的协同，确保在持续丢包流上产出合理冗余或更早回落 TCP，避免“UDP 发满却零校验 + TCP 重传”双重开销；改动后在同一高丢包基线复测。

### 2. 真实外部载体、多上游切换与 Linux Native `epoll` 验收
- **外部载体实机抓包**：覆盖 `RssFixedUdpEntry`（`udpclient` + 外部 `udp2raw`）、`RssSocksCarrier`（嵌套 SOCKS5 如 Hysteria2 `CONNECT` + `UDP ASSOCIATE`）、`RssEgressCodec`（`zlib-stored` vs `none` 双端实测开销），以及多上游混配下的故障切换、热切排空与回滚。
- **Linux `epoll` / `SO_REUSEPORT` 与应用层实测**：
  - 对比 Netty `4.2.21.Final` 在 Linux 原生 `epoll` + `SO_REUSEPORT` 下的长期压测表现。
  - 补充 `game` 对比 `plain`/`daily`/`weak`/`kcptun` 的多随机种子 Linux 基准，以及真实 QUIC/HTTP3、外部 UDP 应用与 WoW 3.3.5a 公网联机验证。
- **`RssDatagramFragmenter` 边界与物理内存实测**：
  - 验证 Linux Pooled Direct Allocator 在高丢包乱序与 OOM 预算下的 backing-capacity 压缩表现。
  - 验证多分片写出时前置分片失败的 Write Promise 聚合行为，并补齐 `MTU=256`、64 分片上限及魔数转义边界测试。

### 3. 生产运维切换（6896 下线 `kcptun` / WoW TLS 验收）
- **6896 (`rGrey`) 切换**：在确认 `udp2raw` 与 `Adaptive UDP` 链路稳定后，将生产 `6896` 切至 `ssCipher: chacha20-ietf-poly1305` + `udpHop: false` + `main-tun`（直连 `udp2raw` `127.0.0.1:4080` / `9910`），下线 `client_linux_amd64` / `server_linux_amd64`（`kcptun`）及其保活 cron（保留 `udp2raw` 保活），对比 `6896` 与 `6898`（跳端口 `daily`）双端抓包。
- **WoW 直连端点**：完成生产 TLS 证书与公网游戏登录全链路验证；后续按需评估 IPv6 支持。

---

## P2 · 中期演进（协议能力与性能扩展）

### 4. 动态路径 MTU（PMTU）探测与动态 `CHUNK`
- **背景**：当前自适应 UDP 帧上限按 IPv4 `1330 B` 静态配置，跨 IPv6（最小路径 MTU `1280 B`）或叠加隧道头时易触发分片或丢包。
- **设计思路**：复用 `Udp2rawMtuState` 思路，实现**已认证、可校验尺寸的低频 PMTU 探针**，按方向/路径缓存有效 MTU 并做指数退避；同时支持 `AdaptiveTcpStream` 动态收缩 `CHUNK` 尺寸以避开路径分片。

### 5. DNS 非地址记录缓存、连接复用与 EDNS
- 当前已完成 `DnsRecordExchange` 多上游故障切换与 `3500 ms` 总截止时间；后续为非 A/AAAA 记录（`HTTPS`、`TXT`、`MX`、`SRV`）补充按 TTL 缓存、上游 socket/连接复用以及完整 EDNS 兼容。

### 6. 双 NAT UDP 打洞 + 公网 TCP 中继（ADR 架构预研）
- **目标**：当通信双方均处于 NAT 后时，由独立公网节点提供 **UDP Rendezvous（打洞协调）+ 已认证 TCP Relay**，支持 NAT 后节点 B 反向建立控制通道（可评估复用 `RrpClient` / `RrpServer`）。
- **前置安全硬约束**（未满足前禁止暴露公网）：逐会话强鉴权、目标端点绑定、ACL 白名单、端到端加密与防重放、背压限流及房间/连接配额。
- **关联清理**：当前旧版 `org.rx.net.transport.UdpClient` 仅因 `UdpHolePunchServer` / `UdpHolePunchClient` / `UdpHolePunchSession` 依赖而保留；待打洞体系迁移至 Transport Core 或退役后彻底删除 `UdpClient`。

---

## P3 · 长期备选 / 暂缓项（按真实瓶颈触发）

- **抗突发丢包编解码**：仅当单 XOR FEC 在 25% 名义冗余预算内无法满足突发丢包目标时，再评估 RS 8:2 或交织 XOR（Interleaved XOR）。
- **TCP 补传有界 UDP 快速重传**：此前独立 Repair Wire 候选已测试淘汰；若未来重启需引入独立能力协商字段。
- **出站伪装与配置扩展**：`egress.udpCodec` 除 `none` 外的编码格式、`RssEgressCodec` 后续扩展协议、以及 `socksServers` 配置项更名为 `upstreams`。
- **Shadowsocks 服务端先发 FIN 半关闭**：当前完整支持客户端先发 FIN 的单向半关闭；若需支持后端先 `shutdownOutput` 且客户端持续读取的双向半关闭，需单独设计。
- **高频路径微优化与结构整理**（当前无瓶颈证据，暂缓）：大包聚合/零拷贝批量写、`SaltHistory` 多域 O(1) 淘汰、以及 P3 级包名重构（`net.handler`/`net.flow`/`net.rss.security`/`net.socks.udp2raw` 等）。
