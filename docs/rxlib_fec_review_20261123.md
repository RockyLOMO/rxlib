# rxlib master · 自适应 FEC 二轮 Review

> 复查日期：2026-11-23。基线：[`5b27f936`](https://github.com/RockyLOMO/rxlib/commit/5b27f936feae7c4f87360067097a685ad5f06fa6)。范围：`UdpDailyFec`、`AdaptiveStream`、`UdpDedupWindow`、相关测试。原则：**弱网稳定性优先，流量其次；尽量少改代码。**

## 结论

低 PPS 组包、TCP 尾包修复及 FEC 接收缓存均已有针对性改善。尚有一个值得优先验证的**正确性风险**：扩至 16 组的 FEC 接收缓存与 64 序号去重窗口不匹配。没有同一弱网环境下的对照数据，不能认定 rxlib 的恢复能力优于 kcptun。

## 上轮问题复查

| 原问题 | 最新变化 | 状态 |
| --- | --- | --- |
| `15ms` 内不足 4 包无 FEC | `MIN_SHARDS=2`；根据包间隔，组包超时约 `15~180ms`；`parityCredit` 控制预算 | 明显改善，仍需验证保护覆盖率 |
| TCP 尾包最短修复 500ms | `MIN_REPAIR=80ms`，增加 RTT/波动估计及尾包测试 | 明显改善 |
| 仅 4 个接收组 | 最大按需扩至 16 组，增加淘汰指标 | 已改善 |
| 同组连续丢多个数据包 | 单 XOR 校验未变；TCP 流仍可通过 TCP 补发 | 仍受限 |
| 拥塞识别与限额 | 尚未联合 RTT 队列增长、突发长度和冗余比例 | 待优化 |
| 弱网对照实验 | 新增测试，但缺同路径 kcptun A/B | 待验证 |

## 剩余问题（按优先级）

### P1 · FEC 缓存和去重窗口错配（正确性风险）

`UdpDailyFec` 接收最多保留 16 个分组，过期时间 300ms，但 `UdpDedupWindow.WINDOW_SIZE=64`。`recover()` 对恢复后的数据调用 `delivered.isDuplicate(seq)`：如果原始包丢失，后续已经收到 80 个新包，校验包才到，尽管早期分组仍在缓存里，恢复出的老序号也可能被窗口判为“过旧”，导致丢弃。

**建议**：仅为 `UdpDailyFec` 配置覆盖最大预期乱序距离的有界去重窗口，避免全局修改其他 UDP 路径；增加“延迟超过 64 序号、但少于 300ms”的恢复回归。

源码：[FEC 恢复](https://github.com/RockyLOMO/rxlib/blob/5b27f936feae7c4f87360067097a685ad5f06fa6/rxlib/src/main/java/org/rx/net/udp/UdpDailyFec.java#L217-L235) · [去重窗口](https://github.com/RockyLOMO/rxlib/blob/5b27f936feae7c4f87360067097a685ad5f06fa6/rxlib/src/main/java/org/rx/net/udp/UdpDedupWindow.java)。

### P1 · 低 PPS 下校验覆盖可能不均匀

`parityCredit` 按每目标 K 个数据包积累冗余预算，但 `MAX_FLUSH=180ms` 会提前关闭短分组。例如 20 PPS、`target=16` 时，可能每积累 16 包，仅最后的 2~4 包得到一份 XOR 校验，其余分组不受保护。**低冗余不等于高恢复率**。

**建议**：补测 20/50/100 PPS、`target=16/8/4`、随机位置丢包和不同时间偏移的恢复率；如有必要才微调低 PPS 分组策略或启用可靠修复。

源码：[发送端分组与预算](https://github.com/RockyLOMO/rxlib/blob/5b27f936feae7c4f87360067097a685ad5f06fa6/rxlib/src/main/java/org/rx/net/udp/UdpDailyFec.java#L47-L83) · [flush](https://github.com/RockyLOMO/rxlib/blob/5b27f936feae7c4f87360067097a685ad5f06fa6/rxlib/src/main/java/org/rx/net/udp/UdpDailyFec.java#L278-L292)。

### P2 · 冷启动 FEC 保护延迟

反馈按 32 个原始数据序号为窗口，并预留 8 个乱序空间；20 PPS 下首次反馈约需 2 秒。发送端开始时 `target=0`，短时会话在反馈建立前不会有 FEC。**建议**：只给“弱网优先”配置提供受控的小额初始冗余，日常省流默认不变。

### P2 · TCP 修复依赖超时且回退较粗粒度

`AdaptiveStream.tick()` 触发超时后调用 `drainToTcp()`，会将所有尚未累计确认的帧经 TCP 发送（可能重复发送已收到但未确认的数据）。**建议**：先统计 `timeoutRepairFrames`、回退字节与真实 P99 卡顿，再决定是否做选择性重发。

### P2 · 单 XOR 对连续多包丢失能力有限

每组仅恢复最多 1 个丢包；普通 UDP 没有可靠补发，TCP 混合模式则有 TCP 修复。缺少按连续丢包长度、队列 RTT 变化调节冗余的闭环。**建议**：先实测；仅在普通 UDP 突发丢包仍是瓶颈时，考虑可选 RS 双校验和拥塞限流。

## 推荐验收

1. **正确性**：晚到校验落后 80~256 包但未超过 300ms；16 组缓存压力；重复/乱序、连续丢 2/3/5 包；尾包无后继数据。
2. **低 PPS**：20/50/100/200/300/1000 PPS × 0/1/5/10/20% 丢包，增加多种随机种子和突发模型。
3. **同路径 A/B**：直连、rxlib 无 FEC、rxlib 自适应 FEC、kcptun 固定 RS，负载、链路与测试时段尽量一致。
4. **指标**：P95/P99 业务交付延迟、最大连续卡顿、有效恢复率、真实收发线速字节、TCP 修复字节、CPU/Netty 堆外内存。

## 最小实施顺序

**优先验证并修复去重窗口错配 → 验证低 PPS 纠错覆盖率 → 补遥测与 A/B → 最后再考虑 RS 和复杂拥塞控制。**

> 本轮是 GitHub 源码及测试的静态审查，未在本地执行 Java 测试或现网压力测试。`docs/java_fec.md` 的完整 RS/拥塞熔断仍属设计文档，不能视作已落地。
