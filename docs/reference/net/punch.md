# NAT 打洞与公网中继（已退役旧版 & ADR 演进说明）

> **状态更新（2026-11-28）**：旧版基于 `org.rx.net.transport.UdpClient` 的 `UdpHolePunchServer` / `UdpHolePunchClient` / `UdpHolePunchSession` / `UdpHolePunchRegistry` 及 `UdpClient` 本体因无生产调用且缺少公网安全鉴权模型，**已于 2026-11-28 彻底退役并从代码库删除**。

## 当前已落地的相关基础能力

- **共享地址发现（`org.rx.net.AddressDiscovery` & `EndpointCandidate`）**：统一收敛 `PublicIpObserver` 公网 IP 投票结果与本机非回环多接口地址候选（IPv4/IPv6、按接口去重与优先级排序、TTL 缓存，不阻塞 EventLoop）。
- **轻量节点注册表（`org.rx.net.registry.NodeRegistry`）**：独立的 H2 冷存 + 有界热索引节点注册表（支持节点属性、服务能力、租约 TTL 过期清理、分页查询与内存/磁盘上限硬约束），与代理及旧版打洞完全解耦。

## 双 NAT 打洞 + 公网 TCP 中继演进方向（ADR-only，尚未实现）

若未来需要实现「双 NAT 后节点直连与失败兜底中继」，必须作为独立模块按 ADR 评审后实施（详见 [rxlib-review-20261128.md](../../rxlib-review-20261128.md) §6.2 与 [roadmap-plan.md](../../roadmap-plan.md)）：

1. **协调层（Rendezvous）**：基于 Transport Core 已鉴权控制通道交换 `AddressDiscovery` 候选地址与公网反射端点，禁止明文无鉴权 UDP 任意房间注册。
2. **直连探测层**：逐会话随机 token + HMAC 校验，验证双向可达性后升级至 `DatagramTransport` / Adaptive 双轨通道。
3. **公网 TCP Relay 兜底层**：仅当直连失败时按会话凭证开启受限中继（可评估复用 `RrpClient`/`RrpServer` 或独立 Relay），强约束逐会话鉴权、目标端点绑定、ACL 白名单、端到端加密防重放、背压限流与房间/连接配额。
