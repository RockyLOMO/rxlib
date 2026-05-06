# 背景

用户在 `rockylomo/rxlib` 的 `master` 分支上询问 `udp2raw` 当前实现如何实现一个“自适应 MTU”。

本任务先按 Review / 新需求前置设计处理：先 review 当前 udp2raw、UDP final MTU guard、冗余与压缩相关实现，产出计划文档；未获得明确“开始改代码”前，不修改业务代码。

# 任务类型判断

本次属于 Review / 新需求设计混合任务：

- Review：需要先理解当前 `udp2raw` 的发送、编码、响应解码、final UDP 出口 MTU guard 以及已有冗余自适应逻辑。
- 新需求：自适应 MTU 属于新增能力，预计需要新增配置、状态对象、探测/反馈协议或启发式回退逻辑、测试用例。

按仓库规则，本阶段只提交计划文档。

# 当前上下文

已 review 的核心文件：

- `rxlib/src/main/java/org/rx/net/SocketConfig.java`
  - 当前存在 `udpMtu` 配置，语义是最终 UDP datagram payload 字节上限，不含 IP/UDP header；0 表示关闭 MTU 限制。
  - 该配置适合继续作为全局硬上限，不适合作为每条 udp2raw tunnel 的动态状态。
- `rxlib/src/main/java/org/rx/net/Sockets.java`
  - 当前 UDP 出口已有统一 final egress guard 设计，最终发送前会根据 `udpMtu` 进行兜底丢弃。
  - 该层只能阻止超限包写出，无法主动调整上游 payload 大小。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawCodec.java`
  - udp2raw frame 当前包含 version、flags、type、session、connId、packetSeq、client/dst/src/auth 等 header 字段。
  - `encode(...)` 负责把 frame header 与 payload 合并为最终 udp2raw datagram。
- `rxlib/src/main/java/org/rx/net/socks/upstream/Udp2rawUpstream.java`
  - request 方向在压缩、冗余、认证后调用 `Udp2rawCodec.encode(...)`，再写入 `state.udpTransportAddress`。
  - 这里是 client-side / upstream request 方向最适合按 tunnel state 限制 payload 的位置。
  - response 解码路径会验证 session、source、auth、seq window，并已有收到包统计与丢弃指标。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawTunnelContext.java`
  - server entry 侧已有 per tunnel context，保存 session、compressConfig、redundantConfig、redundantResolver、redundantStats 等。
  - 适合挂载服务端视角的 per tunnel MTU 状态。
- `rxlib/src/main/java/org/rx/net/socks/UdpRedundantStats.java`
  - 已有类似“自适应”的思想：根据发送、接收、重复包、窗口统计估算丢包，驱动 redundant multiplier resolver。
  - 可复用统计窗口风格，但不能直接把冗余倍率等同 MTU，因为 MTU 需要识别“尺寸相关黑洞/超限丢包”。

关键调用链：

```text
SOCKS UDP / upstream payload
  -> Udp2rawUpstream.writeRequest(...)
  -> optional compression
  -> optional redundant duplicate/copy
  -> auth tag
  -> Udp2rawCodec.encode(...)
  -> Sockets.writeUdp(...)

udp2raw server entry / response
  -> Udp2rawCodec.decode(...)
  -> auth/session/seq validation
  -> optional decompress / redundant dedup
  -> route / write response
```

当前实现意图：

- `udpMtu` 是最终出站 datagram 的硬保护。
- `Udp2rawCodec` 不关心路径 MTU，只做协议编码。
- `Udp2rawUpstream` 和 `Udp2rawTunnelContext` 已经维护 tunnel/session 级状态，是放置自适应 MTU 的合适位置。
- 冗余自适应可以根据丢包率调整复制倍数，但不能解决“包太大被路径丢弃”的问题。

已发现的问题或风险：

- 当前没有看到按 peer/tunnel/path 维护动态 MTU 的状态机。
- 当前没有看到 udp2raw 协议层 ACK 某个 payload size 或探测 size 的机制。
- final guard 丢弃发生太晚，调用方只能知道写结果，无法自动减小下一包大小。
- 单纯根据普通丢包率调低 MTU 可能误判网络拥塞、NAT 抖动、对端处理慢等非 MTU 问题。

# 目标

1. 为 udp2raw 增加 per tunnel / per peer 自适应 MTU 能力。
2. 保持现有 `SocketConfig.udpMtu` 作为 final egress hard cap。
3. 在 udp2raw 编码发送前计算当前 tunnel 的 safe payload 上限，避免 encode 后 datagram 超过路径可承载大小。
4. 支持启动初始 MTU、最小 MTU、最大 MTU、探测步长、回退步长、探测间隔、失败阈值等配置。
5. 优先实现对旧端兼容的方案：旧端不理解 MTU probe/ack 时仍可使用保守回退。
6. 提供明确 metrics，便于线上判断是 MTU 回退还是普通丢包。
7. 添加单元测试覆盖 MTU 估算、回退、恢复探测、与 compression/redundant 的顺序关系。

# 非目标

1. 不替代 `Sockets` final UDP egress guard。
2. 不实现真正依赖 ICMP fragmentation needed 的内核级 PMTUD。
3. 不修改系统 socket MTU、DF bit 或平台相关 raw socket 行为。
4. 不引入重型依赖。
5. 不调整 FEC、HTTP tunnel、TCP 传输逻辑。
6. 不默认改变现有用户配置行为；未开启 adaptive MTU 时保持现状。

# 设计方案

## 总体策略

建议分两阶段实现：

### 阶段 1：无协议变更的保守自适应

新增 `Udp2rawAdaptiveMtuConfig` 与 `Udp2rawAdaptiveMtuState`。

核心行为：

1. 初始 `currentDatagramMtu`：
   - 如果 `SocketConfig.udpMtu > 0`，以它作为 hard cap。
   - 否则默认保守值，例如 1200 或配置值。
2. 每次发送前先估算 udp2raw header overhead：
   - 构造 frame 前已知 flags、client/dst/src/auth 是否存在。
   - auth tag 长度、地址编码长度、header length 可通过 helper 估算。
3. 得到可用业务 payload 上限：
   - `maxPayload = currentDatagramMtu - estimatedUdp2rawHeaderBytes`
   - 如果还启用 redundant，需要注意每个 copy 的 encoded datagram 都必须满足该限制。
4. 如果原始 payload 过大：
   - 不建议在 udp2raw 层悄悄分片，因为当前协议没有重组语义。
   - 对 SOCKS UDP 应按当前 final guard 语义丢弃并记录 metrics，或者在更高层已有分片能力时交给上层。
5. 如果 `Sockets.writeUdp(...)` 返回 MTU 相关 drop 或 encoded size 超出 hard cap：
   - 降低 `currentDatagramMtu`，例如乘以 0.9 或减去 step。
   - 记录 `blackholeCandidate` / `mtuDrop`。
6. 如果连续成功一段时间：
   - 按探测间隔尝试小幅增大 `probeDatagramMtu`。
   - 探测失败则退回上一个 stable MTU。
   - 探测成功则提升 stable MTU。

优点：

- 不改 wire protocol。
- 旧端完全兼容。
- 实现简单，风险低。

缺点：

- 无法区分路径 MTU 丢包和普通丢包。
- 如果大包被路径静默丢弃，本端没有直接反馈，只能从响应缺失或上层超时侧推。
- 对 request-only UDP 流量效果有限。

### 阶段 2：协议级 MTU probe/ack

在 `Udp2rawFrameType` 或 flags 中新增 MTU probe/ack：

- `MTU_PROBE`：携带 probeId、probeSize、可选 padding。
- `MTU_ACK`：返回 probeId、acceptedSize。
- 或在 DATA frame 上增加可选 `FLAG_MTU_PROBE`，对端收到后发轻量 ACK。

推荐新 frame type，避免影响 DATA 解码。

状态机：

```text
STABLE(current=1200)
  -> interval 到期，发送 probe size=1250
PROBING(probe=1250)
  -> 收到 MTU_ACK：current=1250，回 STABLE
  -> probe timeout/drop：current 保持 1200，降低 nextProbe 或延长 interval
LOSS_SUSPECT
  -> 连续 oversized/drop/timeout：current = max(minMtu, current - decreaseStep)，回 STABLE
```

探测包设计：

- 探测包必须经过同一 udp2raw channel、同一加密/auth、同一目标 transport address。
- padding 应在 udp2raw payload 内构造，使最终 datagram size 接近目标 probe size。
- `MTU_ACK` 包本身很小，不参与大包探测。
- 旧端不支持时：握手 capabilities 未声明 adaptive MTU，则不发送 probe，退回阶段 1。

协议兼容：

- 如果已有 `Udp2rawCapabilities` / open request 机制，可新增 capability bit：`ADAPTIVE_MTU`。
- open request/result 中协商是否启用协议级 MTU。
- 未协商时不发送新 frame type，避免旧端将其当 bad frame drop。

## 类职责

### 新增 `Udp2rawAdaptiveMtuConfig`

字段建议：

```java
private boolean enabled;
private boolean probeEnabled;
private int initialMtu = 1200;
private int minMtu = 576;
private int maxMtu = 1400;
private int decreaseStep = 80;
private int increaseStep = 40;
private int successWindow = 64;
private int lossThreshold = 3;
private long probeIntervalMillis = 30000L;
private long probeTimeoutMillis = 3000L;
```

约束：

- setter 中做 JDK8 兼容的 Math clamp。
- `maxMtu` 不能超过 `SocketConfig.udpMtu`，如果 `udpMtu > 0`。
- `minMtu <= initialMtu <= maxMtu`。

### 新增 `Udp2rawAdaptiveMtuState`

职责：

- 保存 current/stable/probe MTU。
- 记录最近成功发送数、drop 数、probeId、probe deadline。
- 提供：
  - `int currentDatagramMtu()`
  - `int maxPayloadBytes(int headerBytes)`
  - `void onWriteAccepted(int datagramBytes)`
  - `void onWriteMtuDrop(int datagramBytes)`
  - `MtuProbe nextProbeIfDue(long now)`
  - `void onProbeAck(long probeId, int acceptedMtu)`
  - `void onProbeTimeout(long now)`

线程模型：

- udp2raw 读写基本回到 channel EventLoop。
- 状态更新应只在 EventLoop 内执行；如有跨线程回调，必须 `relay.eventLoop().execute(...)`。

### 修改 `SocksConfig`

新增：

```java
private Udp2rawAdaptiveMtuConfig udp2rawAdaptiveMtuConfig;
```

默认 `enabled=false`，保持旧行为。

### 修改 `Udp2rawTunnelContext`

新增：

```java
final Udp2rawAdaptiveMtuState adaptiveMtuState;
```

server entry 侧用于 response 方向或 probe ack。

### 修改 `Udp2rawUpstream.TunnelState`

新增：

```java
final Udp2rawAdaptiveMtuState adaptiveMtuState;
```

client/upstream request 方向使用。

### 修改 `Udp2rawCodec`

建议新增 helper：

```java
static int estimateHeaderBytes(Udp2rawFrame frame);
static int encodedAddressBytes(InetSocketAddress address);
static int encodedEndpointBytes(UnresolvedEndpoint endpoint);
```

该 helper 只估算 header，不 retain/release ByteBuf。

如进入阶段 2，还需要新增 frame type 与 probe metadata 编解码。

### 修改 `Udp2rawUpstream.writeRequest(...)`

在 `Udp2rawCodec.encode(...)` 前：

1. 根据即将使用的 flags/frame 估算 header。
2. 从 `adaptiveMtuState.maxPayloadBytes(headerBytes)` 获取 payload 上限。
3. 如果 payload 超限：
   - 记录 `socks.udp2raw.adaptiveMtu.drop.count`。
   - 释放 payload。
   - 返回 false。
4. encode 后得到实际 datagram bytes，再次校验：
   - 如果大于 state current MTU，按 MTU drop 处理，不调用 `Sockets.writeUdp`。
   - 如果小于等于 current MTU，调用 `Sockets.writeUdp`。
5. 根据 `UdpWriteResult` 更新 adaptive MTU state。
   - `ACCEPTED`：`onWriteAccepted(bytes)`。
   - MTU 超限/guard drop：`onWriteMtuDrop(bytes)`。
   - pending/not-writable 不应直接触发 MTU 回退。

### 修改 `Udp2rawHandler`

direct relay 模式下也存在 udp2raw packet wrap/unwrap，可按相同模式在写出前检查 current MTU。

### Metrics

建议新增：

- `socks.udp2raw.adaptiveMtu.current`
- `socks.udp2raw.adaptiveMtu.probe.count`
- `socks.udp2raw.adaptiveMtu.probe.success.count`
- `socks.udp2raw.adaptiveMtu.probe.timeout.count`
- `socks.udp2raw.adaptiveMtu.drop.count`
- `socks.udp2raw.adaptiveMtu.decrease.count`
- `socks.udp2raw.adaptiveMtu.increase.count`

tags：

- `flow=request|response`
- `reason=oversize|write-mtu-drop|probe-timeout`
- `mode=heuristic|probe`

# 修改文件列表

预计新增：

- `rxlib/src/main/java/org/rx/net/socks/Udp2rawAdaptiveMtuConfig.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawAdaptiveMtuState.java`
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawAdaptiveMtuTest.java`

预计修改：

- `rxlib/src/main/java/org/rx/net/socks/SocksConfig.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawCodec.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawFrameType.java`（阶段 2 才需要）
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawCapabilities.java`（阶段 2 才需要）
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawOpenRequest.java`（阶段 2 才需要）
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawOpenResult.java`（阶段 2 才需要）
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawTunnelContext.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/upstream/Udp2rawUpstream.java`

本计划文档：

- `docs/plan/Udp2rawAdaptiveMtu-plan.md`

# 风险点

1. 协议兼容风险
   - 如果直接新增 frame type 但未做 capability 协商，旧端可能 drop 或误判。
   - 阶段 2 必须先协商 capability。

2. 误判风险
   - UDP 丢包不一定是 MTU；普通拥塞、NAT、对端限速都可能导致响应缺失。
   - 阶段 1 应只对明确本地 MTU guard drop 或连续大包失败做保守回退。

3. payload 处理风险
   - udp2raw 层没有重组语义，不应擅自分片业务 payload。
   - 如果需要分片，应另开协议设计。

4. ByteBuf 生命周期风险
   - 超限 drop 路径必须 release。
   - encode 成功交给 Netty 后不能提前 release。
   - redundant 多副本时要避免 retain/release 不平衡。

5. compression/redundant 顺序风险
   - MTU 计算必须发生在 compression 之后、redundant 复制之前或每个 copy encode 前。
   - final datagram size 才是判断依据，不是原始 SOCKS payload size。

6. 线程风险
   - 状态必须限定 EventLoop 更新，避免 ConcurrentMap 以外的共享状态竞争。

7. 性能风险
   - 每包估算 header 不应分配 ByteBuf。
   - metrics 记录要避免高基数 tags。

8. 测试风险
   - 真实路径 MTU 黑洞难以在单元测试稳定复现。
   - 应先用状态机单测 + fake writer / configured `udpMtu` 触发本地 drop。

# 验证方案

本阶段仅提交计划文档，不触发代码 CI。

进入代码实现阶段后，建议验证：

```bash
mvn -pl rxlib -am "-Dmaven.test.skip=true" compile
mvn -pl rxlib -am "-DskipTests" test-compile
mvn -pl rxlib -am "-Dtest=Udp2rawAdaptiveMtuTest" "-Dmaven.test.skip=false" test
mvn -pl rxlib -am "-Dtest=Udp2rawFixedEntryIntegrationTest,UdpPipelineMtuGuardTest" "-Dmaven.test.skip=false" test
```

GitHub Actions：

- 触发 `jdk8-unit-tests.yml`。
- `test_classes` 建议包含：
  - `Udp2rawAdaptiveMtuTest`
  - `UdpPipelineMtuGuardTest`
  - `Udp2rawFixedEntryIntegrationTest`
  - 视改动范围追加 `SocksProxyServerIntegrationTest`

CI 判断：

- 仅当 workflow run `conclusion=success` 才认为通过。
- 如失败，先分类为编译失败、单测失败、格式失败、JDK8 兼容失败或环境问题，再做最小修复。
