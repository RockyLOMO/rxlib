# rxlib 文档导航

文档按 **介绍文档 / 测试文档 / 计划文档** 三类归档，详细模块 API 见 `reference/`。

## 1. 介绍文档（Architecture & Reference）

- [org.rx.net 整体架构介绍](net-architecture.md)（含高清架构图 [PNG](org-rx-net-architecture.png) / [SVG](org-rx-net-architecture.svg)）
- [RSS 内置跳传输与配置说明](rss-transport.md)
- [WoW 直连端点与 DDNS 运维说明](rss-wow-endpoint.md)
- 模块参考手册（`reference/`）：
  - 网络：`reference/net/`（[core](reference/net/core.md) · [transport](reference/net/transport.md) · [rpc](reference/net/rpc.md) · [socks](reference/net/socks.md) · [udp](reference/net/udp.md) · [udp2raw](reference/net/udp2raw.md) · [dns](reference/net/dns.md) · [nameserver](reference/net/nameserver.md) · [http](reference/net/http.md) · [flow-control](reference/net/flow-control.md) · [support](reference/net/support.md) · [punch](reference/net/punch.md) · [ntp](reference/net/ntp.md)）
  - 基础与并发：`reference/`（[ThreadPool](reference/ThreadPool.md) · [ObjectPool](reference/ObjectPool.md) · [WheelTimerShutdown](reference/WheelTimerShutdown.md) · [Config](reference/Config.md) · [DistributedId](reference/DistributedId.md) · [EDB](reference/EDB.md) · [IO](reference/IO.md) · [Nx](reference/Nx.md) · [Util](reference/Util.md)）

## 2. 测试文档（Benchmarks & Test Records）

- [网络传输与协议历史测试/基准记录](test-benchmarks.md)（2026-11-24 ~ 2026-11-27 历次吞吐、延迟、丢包、CPU/内存基准及淘汰方案对照）
- [SOCKS / Shadowsocks / UDP 测试场景索引](test/SocksScene.md)

## 3. 计划文档（Roadmap & Pending Design）

- [未来规划与待实现事项](roadmap-plan.md)（仅保留未落地项、线上待验收项与演进思路）
