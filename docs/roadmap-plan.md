# rxlib 未来规划与待实现计划（Roadmap）

> 本文档仅记录**尚未实现、待验证或未来规划**的演进事项与设计思路。2026-11-28 已完成的 P0/P1/P2 项（SS 用户共享带宽限速与热更新、`weak` 大流 FEC/直通 TCP 优化、Geo 条件增量更新与缺失 `BLOCK` fail-closed / 非 EventLoop 编译、DNS 非地址 RR TTL 实时衰减缓存与字节预算、Nameserver 迁移至已鉴权 Transport Core RPC 并接入可选 `NodeRegistry` 权威源、删除旧版 `UdpClient`/`UdpHolePunch*`/`RssEgressCodec`/`HttpPseudoHeader*`、统一公共 `EgressCodec` (`NONE`/`ZLIB_STORED`/`HTTP` + Server `AUTO`)、`Transport Core v2` AEAD+`zlib3`+`LocalChannel`、`RrpServer`/`RrpClient` 迁移 v2 及 `NodeRegistry` 授权 RAW Relay、`org.rx.net.rendezvous` 双 NAT 协调/防放大 UDP 观测/打洞与直连优先 `PathSelector`、以及 `RssRuntime` opt-in `nodeService` 事务式启停热重载）已移出待办，实测数据见 [测试与基准记录](test-benchmarks.md)。

---

## P1 · 近期优先（真机验收与运维切换）

### 1. 真实外部载体、多上游切换与长期 Linux Native `epoll` 验收
- **外部载体实机抓包**：覆盖 `RssFixedUdpEntry`（外部 `udpclient` + 外部 `udp2raw`）、`RssSocksCarrier`（嵌套 SOCKS5 如 Hysteria2 `CONNECT` + `UDP ASSOCIATE`），以及多上游混配下的故障切换、热切排空与回滚（统一 `EgressCodec` `NONE` / `ZLIB_STORED` / `HTTP` 与 Server `AUTO` 已于 2026-11-28 在高丢包 VPS `182.255.85.247` 完成实测）。
- **Linux `epoll` / `SO_REUSEPORT` 长期压测与应用层实测**：
  - 2026-11-28 已在 `182.255.85.247` 完成短期 4-profile + `SO_REUSEPORT=2` + SS 用户限速热更新、以及统一 `EgressCodec` + `NodeRegistry`/`RendezvousService`/`RrpServer` RAW Relay 实测；后续补充长时间稳态压测、对比 `kcptun` 的多随机种子 Linux 基准，以及真实 QUIC/HTTP3、外部 UDP 应用与 WoW 3.3.5a 公网联机验证。
- **更多双 NAT 组合（全锥/受限锥 NAT）公网打洞直连验收**：
  - 本次已验证多网卡直连优先（局域网直连 TCP + 直连 UDP `6.63 ms` 升级 + 断洞无损修复，VPS 中继流量 `0 B`）与跨运营商对称 NAT（自动按需降级至 E2E AEAD RRP RAW Relay 与 RPC）；后续可在两端均为锥型 NAT 的运营商链路补充公网跨 ISP UDP 打洞升轨直连与中继退避统计。
- **`RssDatagramFragmenter` 边界与物理内存实测**：
  - 验证 Linux Pooled Direct Allocator 在高丢包乱序与 OOM 预算下的 backing-capacity 压缩表现。
  - 验证多分片写出时前置分片失败的 Write Promise 聚合行为，并补齐 `MTU=256`、64 分片上限及魔数转义边界测试。

### 2. 生产运维切换（6896 下线 `kcptun` / WoW TLS 验收）
- **6896 (`rGrey`) 切换**：在确认 `udp2raw` 与 `Adaptive UDP` 链路稳定后，将生产 `6896` 切至 `ssCipher: chacha20-ietf-poly1305` + `udpHop: false` + `main-tun`（直连 `udp2raw` `127.0.0.1:4080` / `9910`），下线 `client_linux_amd64` / `server_linux_amd64`（`kcptun`）及其保活 cron（保留 `udp2raw` 保活），对比 `6896` 与 `6898`（跳端口 `daily`）双端抓包。
- **WoW 直连端点**：完成生产 TLS 证书与公网游戏登录全链路验证；后续按需评估 IPv6 支持。

---

## P2 · 中期演进（待定治理项）

### 3. 现有全局流量整形（`NetworkFlowControl` `GlobalChannelTrafficShapingHandler`）治理（暂缓）
- **现状**：为避免影响现有线上默认行为，本次未修改全局 `GlobalChannelTrafficShapingHandler`（用户级带宽限速已通过独立 `BandwidthLimiter` / `TcpBandwidthPacer` 实现，RRP RAW Relay 已具备独立 4 级待发字节预算与轮询调度）。
- **已识别风险**：`refresh()` 全量遍历更新、高并发连接下共享写缓冲延迟、以及与 `TcpBackpressureHandler` 的叠加行为；后续仅在开启全局限速且出现真实瓶颈时再做专项重构。

---

## P3 · 长期备选 / 暂缓项（按真实瓶颈触发）

- **抗突发丢包编解码**：仅当单 XOR FEC 在 25% 名义冗余预算内无法满足突发丢包目标时，再评估 RS 8:2 或交织 XOR（Interleaved XOR）。
- **TCP 补传有界 UDP 快速重传**：此前独立 Repair Wire 候选已测试淘汰；若未来重启需引入独立能力协商字段。
- **出站伪装与配置扩展**：`egress.udpCodec` 除 `none` 外的编码格式、`EgressCodec` 后续扩展协议（如真 TLS / HTTP2 外壳）、以及 `socksServers` 配置项更名为 `upstreams`。
- **Shadowsocks 服务端先发 FIN 半关闭**：当前完整支持客户端先发 FIN 的单向半关闭；若需支持后端先 `shutdownOutput` 且客户端持续读取的双向半关闭，需单独设计。
- **高频路径微优化与结构整理**（当前无瓶颈证据，暂缓）：大包聚合/零拷贝批量写、`SaltHistory` 多域 O(1) 淘汰、以及 P3 级包名重构（`net.handler`/`net.flow`/`net.rss.security`/`net.socks.udp2raw` 等）。
