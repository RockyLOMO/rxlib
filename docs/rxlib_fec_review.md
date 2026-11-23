# rxlib 弱网 / 自适应 FEC 精简 Review

> 范围：`RockyLOMO/rxlib` 的 `master` 分支（基于 2026-11-23 检查的源码）。目标：**弱网稳定性优先，附加流量其次**；尽量局部修改，不重写现有传输架构。

## 总体结论

`UdpDailyFec` 已有按接收端缺包反馈调整的 **XOR FEC（关闭 / 16:1 / 8:1 / 4:1）**，正常网络可停止发送校验；`AdaptiveStream` 另外使用 **TCP 可靠修复与回退**。这是比单纯固定冗余更省流量的合理架构，但**低 PPS 小包、连续多包丢失、尾包修复延迟**仍是弱网短板。不能仅凭本地单包丢失测试认定弱网表现优于 kcptun。

> 注意：`UdpResilienceConfig` 是另一套默认固定 **XOR 3:1** 的通用 UDP pipeline；`docs/java_fec.md` 中的 Reed–Solomon / 拥塞熔断是设计方案，**不能视为当前已实现**。

## 问题清单（按优先级）

| 优先级 | 问题 / 证据 | 影响 | 最小改进建议 |
|---|---|---|---|
| **P0** | `UdpDailyFec`：`FLUSH=15ms`、`MIN_SHARDS=4`，不足 4 包直接丢弃本组校验机会 | 低发包频率下，即使观察到丢包，也可能完全没有 FEC；提前刷组还会令实际冗余高于目标比值 | 把 PPS / 组包时长纳入决策；低 PPS 使用小组（如 2:1）或主动选择可靠修复；补齐稀疏发包测试 |
| **P1** | `UdpDailyFec`：每组仅 **1 个 XOR parity** | 同组丢 2 个及以上数据包时无法恢复；突发丢包弱于 kcptun 的多校验 RS | 保留 XOR 快路径；先验证 TCP 修复是否足够，再按实际数据考虑选配 RS（如 8:2 / 4:2），避免直接重写 |
| **P1** | `AdaptiveStream`：`MIN_REPAIR=500ms`；缺口可经约 40ms 乱序宽限期请求修复，但尾块缺失可能只能等待发送端超时 | 游戏短流、尾包丢失时可能出现明显卡顿 | 按 RTT / 乱序估计动态修复超时；补充“最后一个包丢失、无后续包”的测试 |
| **P1** | `UdpDailyFec`：接收侧只缓存 **4 个分组**，即使配置 `EXPIRE=300ms` 也可能因新分组提前淘汰旧组 | 高 PPS / 乱序 / 延迟校验包的恢复率下降 | 依据 PPS、乱序窗口设置有限容量；保留上限、淘汰指标，避免内存无限增长 |
| **P2** | 控制器只按每 32 包缺口数量分档，未联合考虑 RTT 排队膨胀及连续丢包长度 | 拥塞丢包时增发 FEC 可能雪上加霜 | 先增加 RTT / burst 监测与冗余上限；确认拥塞后限制冗余并适当 pacing |
| **P2** | 当前测试以规则性单包丢失为主，真实链路记录尚未证明纠错收益 | 无法量化“稳定性提升 / 流量开销” | 建立与无 FEC、kcptun 的同链路 A/B，重点看 P95/P99 和业务层卡顿 |

## 建议实施顺序

1. **第一阶段（低改动、高收益）**：修复低 PPS 组包策略，增加可观测指标（原始缺包、FEC 有效恢复、因 `<4` 分片跳过校验次数、超时修复次数、实际字节开销）。
2. **第二阶段（可靠性）**：改进尾包修复超时、接收组缓存与乱序容忍；保留既有 TCP 回退机制。
3. **第三阶段（按需增强）**：只在 A/B 确认突发丢包仍是瓶颈时，引入可选 RS 多 parity 和基于拥塞的自适应控制；不默认增大流量。

## 验收矩阵

- **发包速率**：20 / 50 / 100 / 200 / 300 / 1000 PPS；重点关注 20–100 PPS 是否仍无有效校验。
- **丢包模型**：0%、1%、5%、10%、20% 随机丢包；连续丢 2 / 3 / 5 包；再叠加乱序、抖动、短时不可达。
- **对照组**：原始链路 / rxlib 关闭 FEC / rxlib 自适应 FEC / kcptun（记录 FEC 参数），相同业务负载与路径。
- **核心指标**：业务完整性与顺序、P95/P99 交付延迟、单次最大卡顿、FEC 恢复率、TCP 修复次数、**总上行+下行真实字节增幅**、CPU 和堆外内存。
- **通过标准**：不得引入重复交付、永久卡住或内存无界增长；弱网 P99 与卡顿应比无 FEC 更好，且网络正常时冗余应接近零（协议头与控制帧仍有开销）。

## 代码定位

- [`UdpDailyFec.java`](https://github.com/RockyLOMO/rxlib/blob/master/rxlib/src/main/java/org/rx/net/udp/UdpDailyFec.java)：组包、缺包反馈、自适应档位、缓存与超时。
- [`AdaptiveStream.java`](https://github.com/RockyLOMO/rxlib/blob/master/rxlib/src/main/java/org/rx/net/transport/AdaptiveStream.java)：UDP 快路径、TCP 重传 / 回退。
- [`UdpResilienceConfig.java`](https://github.com/RockyLOMO/rxlib/blob/master/rxlib/src/main/java/org/rx/net/udp/UdpResilienceConfig.java)：独立的固定 FEC 配置。
- [`UdpDailyFecTest.java`](https://github.com/RockyLOMO/rxlib/blob/master/rxlib/src/test/java/org/rx/net/udp/UdpDailyFecTest.java)、[`AdaptiveStreamTest.java`](https://github.com/RockyLOMO/rxlib/blob/master/rxlib/src/test/java/org/rx/net/transport/AdaptiveStreamTest.java)：现有回归测试。
- [`kcp-go/fec.go`](https://github.com/xtaci/kcp-go/blob/master/fec.go)：kcptun 使用的 Reed–Solomon 纠错实现。

**结论：先修低 PPS 纠错失效和尾包恢复时延，再评估是否引入 RS；不要一上来替换整个传输层。**
