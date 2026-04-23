# UDP 压缩实施计划（配合多倍发包）

## 模式

- 高性能模式
- Java 8 约束
- 目标：在现有 [UdpRedundantSend.md](./UdpRedundantSend.md) 的 UDP 多倍发包链路上增加低延迟、低额外分配、可旁路的单包压缩能力，用于回收冗余带宽开销，但不破坏“零组延迟、立即发送”的现有模型。

## 进度同步（2026-04-23）

> 约定：后续实现、联调、压测推进时，只更新本节日期与状态，不再拆新文档。

- `[已完成]` 需求确认
  - UDP 压缩的首要目标是与多倍发包联用，先支撑试用和灰度，不单独做大范围协议改造。
  - 当前阶段只输出计划文档，不开始代码实现。
- `[已完成]` 现状盘点
  - 已有 [`UdpRedundantEncoder`](../rxlib/src/main/java/org/rx/net/socks/UdpRedundantEncoder.java) / [`UdpRedundantDecoder`](../rxlib/src/main/java/org/rx/net/socks/UdpRedundantDecoder.java) / [`UdpRedundantStats`](../rxlib/src/main/java/org/rx/net/socks/UdpRedundantStats.java)。
  - [`TransportFlags`](../rxlib/src/main/java/org/rx/net/TransportFlags.java) 中已有 `COMPRESS_READ/WRITE`，但当前只用于 TCP stream pipeline 上的 `zlib`，不适合直接套用到 UDP 数据报。
  - 当前仓库没有 `UdpCompress*` 组件、没有 UDP 压缩头、也没有针对 UDP 的压缩统计与旁路机制。
- `[已完成]` 方案初稿
  - 采用“单包无状态压缩 + 可选静态字典 + 自适应旁路”。
  - 出站顺序固定为“先压缩，后多倍发送”；入站顺序固定为“先去重，后解压”。
  - 初版默认算法倾向 `LZ4 fast`，不默认采用 `zlib/gzip`。
- `[已完成]` 文档初稿
  - 已输出协议头、Pipeline 顺序、配置建议、实施拆分、测试与监控方案。
- `[已完成]` 代码实现
  - 已新增 `UdpCompressConfig`、`UdpCompressEncoder`、`UdpCompressDecoder`、`UdpCompressStats`。
  - 已在 `Sockets` 中统一接入 UDP 压缩与多倍发包，顺序为“入站先去重后解压，出站先压缩后多发”。
  - 当前实现阶段仅支持 `dictionaryId = 0`，静态字典仍留待下一阶段。
- `[已完成]` 自动化验证
  - 已补充 `UdpCompressTest` 单元测试，覆盖压缩头、透传、去重后解压、引用计数释放、非法字典包丢弃。
  - 已补充 `SocksProxyServerIntegrationTest#socks5UdpRelay_chained_withUdpCompressAndRedundant_e2e`。
  - 已执行 `UdpCompressTest`、`UdpRedundantTest`、`SocksProxyServerIntegrationTest`、`Socks5ClientIntegrationTest`、`Udp2rawHandlerTest` 定向回归。

## 1. 背景与目标

当前 UDP 多倍发包已经可以用带宽换到达率，但副作用也很明确：

- `multiplier = N` 时，on-wire 带宽近似放大到 `N` 倍。
- 在小包、高频、弱丢包场景中，多倍发包能显著压低尾延迟，但链路成本上升明显。
- 若直接对所有 UDP 包做通用压缩，容易在不可压流量上白白消耗 CPU，反而抬高 p99 延迟。

因此本次 UDP 压缩的目标不是“让所有 UDP 都压缩”，而是：

1. 在 **多倍发包链路** 上尽量回收可压缩流量的带宽膨胀。
2. 保持 **一个数据报进、一个数据报出**，不引入分片重组、不引入凑包等待。
3. 对 **小包、已加密、高熵、低收益** 流量自动旁路，避免热点路径做无用功。
4. 保持 **双端显式兼容**，不破坏当前非压缩部署。

## 2. 范围与非目标

### 2.1 本期范围

- SOCKS5 UDP relay
- Udp2raw 路径
- 与 `UdpRedundant` 组合使用
- 配置、监控、灰度与回退能力

### 2.2 本期明确不做

- 跨包增量压缩
- 基于历史包的差分编码
- 压缩后再分片重组
- 全局自适应字典训练
- 与 FEC 三者同时联用的完整矩阵支持

## 3. 设计原则

### 3.1 单包无状态

每个 UDP 包必须独立可解码，不依赖前一个包是否到达，也不依赖乱序恢复。这是本方案的首要约束。

### 3.2 先压缩，后冗余

逻辑数据流固定如下：

```text
Outbound:
业务 payload -> UdpCompressEncoder -> UdpRedundantEncoder -> wire

Inbound:
wire -> UdpRedundantDecoder -> UdpCompressDecoder -> 业务 payload
```

这样做的原因：

- 压缩只做一次，再复制压缩结果 `N` 份，CPU 成本最低。
- 接收端先去重，再解压，避免同一逻辑包被重复解压 `N` 次。
- 不需要改变现有 `UdpRedundant` 的去重模型。

### 3.3 强旁路而不是强压缩

满足任一条件就直接透传：

- 包太小
- 试压后收益不达标
- 已知高熵或已加密流量
- 目标端未启用该能力
- 当前 channel 进入压缩冷却期

### 3.4 不阻塞 EventLoop

- 不允许在 I/O 线程执行阻塞 I/O。
- 压缩实现必须倾向纯内存、低分支、低分配。
- 如果后续算法实测 CPU 抖动过大，要优先降级为旁路，而不是把重型压缩塞进 EventLoop。

## 4. 协议头设计

### 4.1 基本策略

- **仅在压缩成功且收益达标时** 才附加压缩头。
- 未压缩的数据报继续原样透传，不增加头部。
- 压缩头独立于 `RDNT` 冗余头，避免修改现有已落地协议。

### 4.2 建议头格式

压缩包格式：

```text
+-------------------+-------------------+
| UCMP_MAGIC (4B)   | ORIG_LEN (2B)     |
+-------------------+-------------------+
| FLAGS (1B)        | DICT_ID (1B)      |
+-------------------+-------------------+
|         compressed payload            |
+---------------------------------------+
```

- `UCMP_MAGIC`：建议使用 ASCII `UCMP`
- `ORIG_LEN`：原始 payload 长度，便于解压后做长度校验
- `FLAGS`：预留位；初版只使用 `DICT_USED`
- `DICT_ID`：`0` 表示无字典，`>0` 表示使用预共享静态字典版本

### 4.3 与多倍发包叠加后的 on-wire 结构

```text
+-------------------+-------------------+-------------------------------+
| RDNT_MAGIC (4B)   | SEQ_ID (4B)       | UCMP Header + compressed body |
+-------------------+-------------------+-------------------------------+
```

说明：

- `RDNT` 仍然位于最外层，保证接收端先去重。
- 对于未压缩的包，不附加 `UCMP` 头，仍可直接走现有冗余协议。
- 对端若未部署 `UdpCompressDecoder`，则无法正确恢复压缩负载，因此 **压缩能力要求双端一致启用**。

## 5. 算法与判定策略

### 5.1 初版算法选择

首版建议默认选择 **LZ4 fast**，理由：

- 单包压缩和解压延迟低
- 适合小包和实时路径
- 更符合“低尾延迟优先”而非“极限压缩率优先”的目标
- 当前仓库尚未引入现成的 `LZ4` 依赖，实现阶段需要先完成压缩库选型，优先纯 Java、低分配实现

初版不建议默认使用 `zlib/gzip`：

- CPU 成本更高
- 对 EventLoop p99 更不友好
- 小包收益通常不稳定

### 5.2 静态字典策略

针对游戏/控制类小包，纯 LZ4 在几十到几百字节区间的收益可能不稳定，因此建议预留 **静态字典**：

- 字典通过离线采样训练并随版本发布
- 双端按 `dictId` 预置
- 解码时只依赖 `dictId`，不依赖上一个包
- 初版可先支持 `dictId = 0`，字典实现放在第二阶段落地

### 5.3 旁路阈值建议

建议默认阈值如下：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `minPayloadBytes` | 96 | 小于该长度直接跳过 |
| `minSavingsBytes` | 24 | 至少节省这么多字节才值得 |
| `minSavingsRatio` | 0.12 | 至少节省 12% 才真正启用压缩 |
| `adaptiveBypassWindow` | 30s | 连续低收益时临时旁路 |

压缩判定公式建议：

```text
use_compress =
    payloadLen >= minPayloadBytes
    && compressedLen + ucmpHeaderLen < payloadLen
    && (payloadLen - compressedLen - ucmpHeaderLen) >= minSavingsBytes
    && ((payloadLen - compressedLen - ucmpHeaderLen) / payloadLen) >= minSavingsRatio
```

### 5.4 不建议压缩的流量

- 已经是加密流量的 UDP 负载
- 已知高熵或随机噪声较高的流量
- 已经非常接近 MTU 上限且压缩收益不稳定的负载

## 6. Pipeline 与接入点设计

### 6.1 逻辑顺序

逻辑顺序固定为：

- 出站：`UdpCompressEncoder -> UdpRedundantEncoder`
- 入站：`UdpRedundantDecoder -> UdpCompressDecoder`

### 6.2 Netty 注册顺序约束

为了同时满足入站和出站顺序，后续实际注册顺序建议为：

```text
UdpRedundantDecoder
UdpCompressDecoder
UdpRedundantEncoder
UdpCompressEncoder
```

这样在 Netty 中：

- 入站先过 `UdpRedundantDecoder`，再过 `UdpCompressDecoder`
- 出站先过 `UdpCompressEncoder`，再过 `UdpRedundantEncoder`

### 6.3 计划接入点

优先考虑统一收口在 [`Sockets.java`](../rxlib/src/main/java/org/rx/net/Sockets.java)：

- 现有 `addRedundantHandlers(...)` 适合扩展为更通用的 UDP 优化挂载入口
- `udpBootstrap(...)` 已是 UDP pipeline 自动装配点
- `SocksProxyServer` / `SocksUdpRelayHandler` / `Udp2rawHandler` 当前都可沿用该入口接入

建议后续演进方向：

1. 保留现有 `addRedundantHandlers(...)` 兼容入口。
2. 新增 `addUdpOptimizationHandlers(...)`，内部按配置决定是否挂接压缩与冗余。
3. 逐步让现有调用方切到统一入口，减少不同路径手工拼 pipeline 的分叉。

## 7. 配置设计建议

建议新增独立配置对象：`UdpCompressConfig`

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | boolean | false | 是否启用 UDP 压缩 |
| `codec` | enum/string | `LZ4_FAST` | 初版默认算法 |
| `minPayloadBytes` | int | 96 | 小包直接旁路 |
| `minSavingsBytes` | int | 24 | 压缩收益门槛 |
| `minSavingsRatio` | double | 0.12 | 压缩收益比例门槛 |
| `dictionaryId` | int | 0 | 0 表示无字典 |
| `adaptiveBypass` | boolean | true | 是否按收益做临时旁路 |
| `adaptiveBypassWindowSeconds` | int | 30 | 压缩冷却窗口 |
| `destinationRules` | List | 空 | 分目的地启用/禁用与覆盖参数 |

与 `UdpRedundantConfig` 的关系建议如下：

- 二者解耦，分别建模，避免把压缩和冗余耦合进一个超大配置类。
- 在 `SocksConfig` 上允许同时挂 `udpCompressConfig` 与 `udpRedundantConfig`。
- 当两者都启用时，统一由一个入口按固定顺序装配 pipeline。

## 8. 统计与自适应旁路

本方案建议引入轻量统计，但 **不做服务端全局聚合自适应**。

### 8.1 建议统计维度

- `attemptPackets`
- `appliedPackets`
- `bypassTinyPackets`
- `bypassIncompressiblePackets`
- `savedBytes`
- `expandBytes`
- `encodeMicros`
- `decodeMicros`
- `decodeErrorPackets`
- `dictMismatchPackets`

### 8.2 自适应策略

建议按 **channel 或目标端点** 维护滚动窗口：

- 连续低收益则旁路一段时间
- 旁路窗口结束后抽样恢复压缩
- 不按整个服务端主 UDP channel 统一做全局收益判断，避免不同客户端流量相互污染

## 9. 实施拆分

### 阶段 1：协议与配置骨架

- 新增 `UdpCompressConfig`
- 明确 `UCMP` 头格式和版本约束
- 明确与 `UdpRedundantConfig` 的挂载关系
- 明确压缩库依赖选型与引入方式

### 阶段 2：核心编解码

- 新增 `UdpCompressEncoder`
- 新增 `UdpCompressDecoder`
- 完成透传、压缩、解压、长度校验、字典占位支持
- 确保 `ByteBuf` 引用计数正确，无中间缓冲泄漏

### 阶段 3：Pipeline 接入

- 接入 `Sockets` UDP 自动装配路径
- 接入 SOCKS5 UDP relay
- 接入 Udp2raw
- 确保与 `UdpRedundant` 组合顺序正确

### 阶段 4：统计与灰度

- 接入 `UdpCompressStats`
- 接入目的地规则和收益旁路
- 增加必要诊断指标与日志

### 阶段 5：回归与压测

- 单元测试
- 集成测试
- `tc netem` 人工压测
- 带宽、p99、堆外内存回归确认

## 10. 测试与验收计划

### 10.1 单元测试

至少覆盖：

- 未命中压缩条件时原样透传
- 命中阈值时正确追加 `UCMP` 头并可解压回原文
- `dictId = 0` 与未来非零字典的兼容判断
- `ORIG_LEN` 不匹配时丢弃并计数
- 与 `UdpRedundant` 叠加时只解压一次
- 原始 `DatagramPacket` 和中间 `ByteBuf` 引用释放正确

### 10.2 集成测试

至少覆盖：

- SOCKS5 UDP associate：压缩 + 多倍发包联用
- Udp2raw：压缩 + 多倍发包联用
- 双端都启用压缩时互通正常
- 一端启用一端未启用时，按预期视为不兼容部署，不允许误判为普通 UDP

### 10.3 手工压测

建议至少记录三组对比：

1. 无压缩、无冗余
2. 仅多倍发包
3. 多倍发包 + UDP 压缩

关注指标：

- 端到端平均时延与 p99
- 丢包率下的游戏/控制包到达率
- on-wire 带宽
- 堆外内存占用
- CPU 使用率

## 11. 风险清单

### 11.1 协议兼容风险

- 压缩不是透传增强，而是协议增强。
- 一端启用压缩、另一端未升级时，业务 payload 会被破坏。
- 因此必须做 **双端能力对齐** 和 **按目的地灰度启用**。

### 11.2 性能风险

- 对不可压流量强行压缩，会白白增加 CPU 与尾延迟。
- 若使用高压缩率算法，会侵占 EventLoop 预算。
- 若中间缓冲重复分配过多，可能导致 Direct Memory 抖动。

### 11.3 内存泄漏风险

- 压缩成功、压缩失败、旁路三条分支都要审计 `ByteBuf` 生命周期。
- 尤其要确认与 `UdpRedundantEncoder` 组合后，中间缓冲不会被多次释放或遗忘释放。

### 11.4 MTU 风险

- 压缩头本身有额外开销。
- 如果收益不足，反而可能让数据报更接近 MTU，增加 IP 分片概率。
- 因此必须把最终 on-wire 长度作为启用条件之一。

## 12. 监控指标建议

至少补充以下指标：

- `udp_compress_attempt_packets`
- `udp_compress_applied_packets`
- `udp_compress_bypass_tiny_packets`
- `udp_compress_bypass_incompressible_packets`
- `udp_compress_saved_bytes`
- `udp_compress_expand_bytes`
- `udp_compress_encode_micros_p50`
- `udp_compress_encode_micros_p99`
- `udp_compress_decode_micros_p50`
- `udp_compress_decode_micros_p99`
- `udp_compress_decode_error_packets`
- `udp_compress_dict_mismatch_packets`
- `udp_wire_bytes_before_redundant`
- `udp_wire_bytes_after_redundant`
- `netty_direct_memory_used_bytes`

## 13. 当前结论

当前已落地路线如下：

1. 已实现 **无字典 LZ4 fast + 强旁路**。
2. 已覆盖 **SOCKS5 UDP relay** 与 **Udp2raw** 的公共 UDP pipeline。
3. 已支持与 **UdpRedundant** 联用，不扩散到其他 UDP 协议路径。
4. 下一阶段重点转向 **静态字典**、更细粒度监控指标和 `tc netem` 压测数据沉淀。

该路线对当前仓库最稳妥，能最大化复用现有 `UdpRedundant` 架构，同时把协议、性能和内存风险控制在可回退范围内。
