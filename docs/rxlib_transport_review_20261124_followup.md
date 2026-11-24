# rxlib RSS Transport Review 与下一轮修改清单

日期：2026-11-24（第二轮当日复查）
基线：[master 2ade72bc](https://github.com/RockyLOMO/rxlib/commit/2ade72bcc4fad7c20fd64283e92ea257a30d8f2f)，北京时间 21:17；相对上一轮基线 5b27f936 新增 15 个提交。

## 1. 目标与结论

- 数据正确性、认证安全及稳定交付是底线；daily/weak 省实际双向流量优先、CPU 效率其次，允许有界内存换取收益。
- game 以交付率和尾延迟为优先目标，避免无效冗余；整机目标仍为 1 vCPU / 768 MiB。
- 保留 plain 默认语义、三个业务 profile 的 zlib 3、已有端口安全旁路及显式覆盖；压缩先于 FEC。无需默认升级 zlib 6。
- weak 的字节预算、SACK、提前尾包修复已明显改善部分弱网负载；下一步以小范围消除无效流量和稳定性验证为主，不继续扩大协议复杂度。
- 本文为源码静态复查与已有仓库测试记录分析，不是独立执行的基准测试报告。仅提交文档，不实施修改。

## 2. 上一轮问题跟踪

| 问题 | 本轮状态 |
| --- | --- |
| 64 位 FEC 去重窗口过小 | 已扩至 512 位，并增加延迟校验、序号回绕测试 |
| 低 PPS 无效 XOR | daily 支持短前缀暂存、按需合并，仍保持原包立即发送 |
| TCP 接管后的待发送校验 | 已支持 cancelPendingParity |
| 多缺口重复修复 | weak 增加 SACK 与可靠前缀跟踪 |
| 尾包等待超时 | weak 增加单次 UDP 提前修复，保留 TCP 兜底 |
| 固定 64 帧窗口 | game/weak 按需扩大至 256，增强恢复预算 32 MiB/JVM |
| 实际字节 FEC 预算 | weak 按编码后字节计费；daily 保持包数预算 |
| 独立 game 模式 | 已有方向独立反馈、1–3 倍发送、会话/进程额外包预算，默认 1x |
| 目录职责 | RSS 私有 tcp/udp/profile 已拆分，通用压缩保留兼容门面 |
| 连续丢包 | 单 XOR 每组只能修一个缺失分片；依靠可靠修复弥补 TCP 数据 |
| 拥塞 | 已有背压/预算等措施，仍待长时、多流压力验证 |

## 3. 已有实测对照

数据来自 [架构 Review 的实施与验收记录](https://github.com/RockyLOMO/rxlib/blob/2ade72bcc4fad7c20fd64283e92ea257a30d8f2f/docs/rxlib_net_architecture_review_20261124.md#L164-L216)。1 vCPU / 768 MiB Linux cgroup，150 ms RTT、双向各 5% 随机丢包，100 次顺序 TCP 回显、每方向每次 164 B；按双向 IPv4 字节统计。kcptun 使用 10:1、fast、AES 和默认压缩。

| 随机种子 | 旧 weak IP B / P99 ms | 新 weak（UDP probe）IP B / P99 ms | kcptun 10:1 IP B / P99 ms |
| --- | ---: | ---: | ---: |
| 718 | 27,259 / 1168.3 | 38,968 / 676.5 | 110,036 / 694.9 |
| 2063 | 28,526 / 640.6 | 35,387 / 341.7 | 108,960 / 576.8 |
| 3491 | 31,907 / 580.2 | 38,083 / 411.5 | 108,620 / 581.9 |

三个种子中新 weak 比 kcptun 约省 65% 双向 IP 流量，P99 与最大停顿均满足同种子 kcptun 1.2 倍门槛；相较旧 weak 有流量增加，但尾延迟改善。服务端 JVM RSS 峰值样本 135.5–142 MiB，不代表整机峰值；CPU 应分服务端 JVM、额外隧道进程及整个 cgroup 分别比较。

80 ms RTT、双向突发损失、短回显与 128 KiB 文件流并发的补充样本：weak 189,159 B / P99 460.7 ms；kcptun 281,895 B / P99 413.1 ms。不能据此宣称全场景优于 kcptun。

game 冷 JVM 新会话 1000 PPS 的种子 626：plain 1803/2000 回包、P99 175.8 ms；game 1826/2000、P99 255.9 ms。热会话表现改善，但并非所有冷/热新会话和负载突变均达 P99 门槛。回包 P99 不包含丢失包或初始化时间；单独冷态诊断显示 JIT 和 cgroup CPU 节流可能是重要因素，不能断言 MAC 是主要瓶颈。

## 4. 下一轮修改清单（供实现）

按下列顺序独立提交。优先 P1，不要求一次性实现 P2。每项先补复现/基准，再决定是否需要改变线上协议；若收益不明显，保留现状。

### P1-01：分离 weak UDP repair 与正常 FEC 编组

源码：[AdaptiveStream.probeTail](https://github.com/RockyLOMO/rxlib/blob/2ade72bcc4fad7c20fd64283e92ea257a30d8f2f/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveStream.java#L353-L372)、[UdpDailyFec.write](https://github.com/RockyLOMO/rxlib/blob/2ade72bcc4fad7c20fd64283e92ea257a30d8f2f/rxlib/src/main/java/org/rx/net/rss/udp/UdpDailyFec.java#L64-L123)。

当前提前尾包调用 `fec.write(last, udpOut)`，将重发的同一逻辑流帧作为新的 FEC 序号参与普通发送、丢包观测及字节信用累积。可能产生额外 XOR、校验和反馈偏差，尚未量化真实影响。

- [ ] 先增加探测次数、探测成功、后续 TCP repair、重复交付过滤和 parity 增量的统计。
- [ ] 实验独立 repair 数据路径：同一逻辑流序号、新外层认证序号；不计入原始 FEC 分片采样和 parityByteCredit，不进入新的 XOR 组。
- [ ] 如果新增帧类型/改变线格式，必须明确能力协商与旧客户端回退；不得偷改已部署 weak 线协议。
- [ ] 保持单次探测、原编码字节、认证、防重放、累计 ACK 和最终可靠 TCP 修复。
- [ ] 测试原包/探测分别丢失、乱序、FEC 同时到达、ACK 丢失/迟到、序号回绕、FIN/close；交付严格一次且有序。
- [ ] 用现有 weak 三种子及突发混合负载 A/B，确认净流量下降且 P99/最大停顿不明显恶化；若收益不足，不引入新 wire type。

### P1-02：减少 weak 纯 TCP 回退阶段的无效应用层 ACK

源码：[AdaptiveStream.data/requestAck](https://github.com/RockyLOMO/rxlib/blob/2ade72bcc4fad7c20fd64283e92ea257a30d8f2f/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveStream.java#L287-L340)、[tcp ACK 处理](https://github.com/RockyLOMO/rxlib/blob/2ade72bcc4fad7c20fd64283e92ea257a30d8f2f/rxlib/src/main/java/org/rx/net/rss/tcp/AdaptiveStream.java#L127-L150).

`peerProbed` 在收到 PING 后置 true，后续纯 TCP DATA 也会触发应用层 ACK；发送端无 pending 时直接忽略。长期 TCP 回退存在潜在冗余流量。

- [ ] 增加 ACK 类型/路径/无 pending 忽略次数及实际 IP 流量统计。
- [ ] 明确区分需要累计确认的可靠修复和正常 TCP 数据（可先探索无需改变 wire format 的安全方案）。
- [ ] 禁止简单删除 weak ACK 条件：孤立尾包可靠修复、重复包、SACK/GAP 与 UDP 恢复时仍需及时确认，避免永久 pending 或重复回退。
- [ ] 覆盖 UDP 双向/单向阻断、长期纯 TCP、恢复 UDP、UDP/TCP 乱序、窗口满、FIN；校验数据完整性及无重复交付。
- [ ] 长文件下载及 UDP 阻断场景验证 ACK 字节减少，且弱网短回显不退化。

### P1-03：game 冷启动和单核调度优化

源码：[UdpGameRedundancy](https://github.com/RockyLOMO/rxlib/blob/2ade72bcc4fad7c20fd64283e92ea257a30d8f2f/rxlib/src/main/java/org/rx/net/rss/udp/UdpGameRedundancy.java)、[DailyUdpWire.sendGame](https://github.com/RockyLOMO/rxlib/blob/2ade72bcc4fad7c20fd64283e92ea257a30d8f2f/rxlib/src/main/java/org/rx/net/rss/udp/DailyUdpWire.java#L321-L357).

- [ ] 保留默认 1x 冷启动，不盲目改回 2x 或增加 4x/5x。
- [ ] 区分冷 JVM、热 JVM 新会话、热会话和低→高 PPS 突变；单独统计关联初始化、业务首次交付和稳态。
- [ ] 实验服务启动后的有限预热（JIT、认证/缓冲常用路径），不向真实业务注入虚假包，不要求每个会话重复预热。
- [ ] 优先检查已有 MAC 对象复用、EventLoop 排队、分配次数、内核 RcvbufErrors、CPU 节流；没有 profile 证据不重写认证。
- [ ] 同时报告交付率、丢失/超时、P95/P99/最大停顿、IP 字节、CPU、RSS/direct、GC；冷/热各多次重复。
- [ ] 不仅优化原生 UDP 游戏，也针对实际 TCP 游戏验证 AdaptiveStream；UDP 多发不会自动作用于 WoW 等 TCP 游戏。

### P2-01：weak 极低 PPS 反馈

源码：[UdpDailyFec.observe / acceptReport](https://github.com/RockyLOMO/rxlib/blob/2ade72bcc4fad7c20fd64283e92ea257a30d8f2f/rxlib/src/main/java/org/rx/net/rss/udp/UdpDailyFec.java#L183-L237)、[expireSender](https://github.com/RockyLOMO/rxlib/blob/2ade72bcc4fad7c20fd64283e92ea257a30d8f2f/rxlib/src/main/java/org/rx/net/rss/udp/UdpDailyFec.java#L323-L330).

32 包反馈周期、8 包乱序容忍、5 秒反馈超时，低于约 6.4 PPS 时连续发送的反馈周期可能超过 5 秒；初始反馈需约 40 个序号。daily 稀疏分组覆盖率低属于省流权衡，不是必须修复的错误。

- [ ] 仅针对 weak 测试有活动时按包数或时间触发的有限反馈；明确样本范围及有效样本数、认证、去重与反馈上限。
- [ ] 不把无反馈当作零丢包，不在完全空闲时制造额外心跳，不强制短组全部发送 parity。
- [ ] 覆盖 1/2/5/10/20 PPS、非均匀包间隔、反馈丢失、乱序和突发恢复；验证交付质量及真实双向字节。
- [ ] 若需要新的报告格式，另行能力协商；保持 daily 行为。

### P2-02：进一步验证突发丢包与多连接资源边界

- [ ] 复测 1/5/10% 双向与非对称丢包、30/80/150/300 ms RTT、连续 2/3/5/10 包丢失、限速和 jitter。
- [ ] 多个短流与长文件并发，UDP 20/200/300/1000 PPS，长时间运行与热更新/重连；检测资源释放、全局恢复预算及会话公平性。
- [ ] 记录真实双向 IP 字节（含控制、修复、认证、探测、TCP 重传）、有效载荷、交付率、CPU 秒/GiB、EventLoop 延迟、GC、整机内存及 P99/最大停顿。
- [ ] 仅在实测证明单 XOR 无法满足目标时对照同预算 RS 8:2 或交织 XOR；不默认增大冗余。普通 UDP 不承诺可靠重传。

## 5. 统一验收与回归

- 弱网基准：沿用相同负载和计数点；原 150 ms/5% 三种子样本目标为 P99 和最大停顿不超过同种子 kcptun 10:1 的 1.2 倍，40,000 B 是该负载的候选预算而非生产固定上限。优先比较新旧 weak 实际字节及交付质量。
- game：冷/热状态分开测试；统计失败/超时和交付率，不用只统计成功回包的 P99 代替完整体验；对照 plain。
- 单测：AdaptiveStreamTest、AdaptiveUdpPortTest、UdpDailyFecTest、UdpDailyFecCoverageTest、UdpGameRedundancyTest、DailyUdpWireTest、UdpSequenceWindowTest；补充 SOCKS/relay 集成、旧版协商、认证重放、ByteBuf 引用计数与窗口预算回收。
- 资源：1 vCPU / 768 MiB 整机限制，区分 JVM RSS、Netty direct、内核缓冲和其他进程；不能用单个 JVM RSS 证明整机达标。
- 不为了性能降低认证安全、不丢弃有效业务来制造省流，不默认改变用户的 zlib/端口策略。

## 6. 架构边界与暂缓事项

本轮已完成主要职责拆分；保持 `org.rx.util.rss` 用户/部署配置、`org.rx.net.socks` 控制面、`org.rx.net.rss.{profile,tcp,udp}` RSS 私有数据面、`org.rx.net.{transport,udp,handler}` 通用能力的目标方向。当前依赖并未完全单向，不为形式整洁强行改写公共 API。

- 暂缓完整 KCP ARQ、默认 RS、新增永久高倍冗余、默认 zlib 6，以及大规模目录重构。
- 不复制三份 profile 实现；通用能力按功能聚合，保留历史 FQCN/配置、RPC DTO 和 pipeline 兼容。
- 先修复上述 P1 再决定 P2；若基准不能证明改动净收益，维持当前稳定实现。

参考：[Profile 最终策略](https://github.com/RockyLOMO/rxlib/blob/2ade72bcc4fad7c20fd64283e92ea257a30d8f2f/docs/rxlib_transport_profiles_final.md)、[网络架构 Review](https://github.com/RockyLOMO/rxlib/blob/2ade72bcc4fad7c20fd64283e92ea257a30d8f2f/docs/rxlib_net_architecture_review_20261124.md)、[RSS Transport](https://github.com/RockyLOMO/rxlib/blob/2ade72bcc4fad7c20fd64283e92ea257a30d8f2f/docs/rss-transport.md).
