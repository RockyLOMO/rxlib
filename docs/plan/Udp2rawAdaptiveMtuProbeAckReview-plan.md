# 背景

用户指定 review `rockylomo/rxlib` 的 `agent/udp2raw-adaptive-mtu-plan` 分支，并说明代码已经实现协议级 `MTU_PROBE/MTU_ACK`，本次 review 不需要考虑旧协议兼容性。

本阶段按 Review / 修复类任务处理：先 review 当前实现、提交 review 计划文档；未获得明确“开始修改代码”前，不修改业务代码。

# 任务类型判断

本次属于 Review / 修复 / 优化需求。

原因：

- 代码已经在 `agent/udp2raw-adaptive-mtu-plan` 分支实现，用户要求“再 review 下”。
- 需要检查协议级 probe/ack 的状态机、调用链、ByteBuf 生命周期、MTU hard cap、双向路径探测和测试覆盖。
- 当前阶段只做 review 和计划文档提交，不直接修改业务代码。

# 当前上下文

已 review 的文件：

- `rxlib/src/main/java/org/rx/net/socks/Udp2rawMtuState.java`
  - 新增 per-side MTU 状态，支持 `nextProbe`、`ack(seq, acceptedMtu, now)`、`onWriteMtuDrop`、timeout 下调。
  - 默认 `Udp2rawMtuState(int initialMtu, String side)` 使用 `MIN_MTU=1200`，并把 `maxMtu` 设为 `initialMtu`。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawFrameType.java`
  - 新增 `MTU_PROBE(5)` 和 `MTU_ACK(6)`。
- `rxlib/src/main/java/org/rx/net/socks/upstream/Udp2rawUpstream.java`
  - client/upstream tunnel 初始化后调度 `scheduleMtuProbe`。
  - `sendMtuProbe` 生成带 auth tag 的 MTU_PROBE，并使用 `Sockets.UdpMtuProbeDatagramPacket` 绕过本地 final MTU guard。
  - `decodeResponse` 能处理 `MTU_ACK`，并从 ACK payload 读取 accepted MTU。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawServerEntryHandler.java`
  - server entry 收到 `MTU_PROBE` 后验证 auth，并回复带 4 字节 accepted MTU 的 `MTU_ACK`。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawPayloadSupport.java`
  - `writeEncoded` 增加 `Udp2rawMtuState` 参数，MTU_EXCEEDED 时回调 `onWriteMtuDrop`。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawTunnelContext.java`
  - server tunnel context 增加 `Udp2rawMtuState`。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawSession.java`
  - response 方向写回 peer 时使用 `context.currentMtu()` 和 `context.mtuState`。
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawMtuStateTest.java`
  - 已覆盖 accepted ACK clamp、write MTU drop 保守下调、默认不超过 hard initial MTU。
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawFixedEntryIntegrationTest.java`
  - 已覆盖 fixed entry 对 authenticated MTU_PROBE 回复 ACK，并校验 ACK payload 为 4 字节 accepted MTU。
- `rxlib/src/test/java/org/rx/net/socks/UdpRedundantTest.java`
  - 已覆盖 dynamic MTU pre-drop 和 final MTU drop feeding state。

关键调用链：

```text
client request MTU probe:
Udp2rawUpstream.completeTunnelInit
  -> scheduleMtuProbe
  -> runMtuProbe
  -> Udp2rawMtuState.nextProbe
  -> sendMtuProbe
  -> Sockets.writeUdp(UdpMtuProbeDatagramPacket)

server ACK:
Udp2rawServerEntryHandler.channelRead0
  -> Udp2rawCodec.decode
  -> handleMtuProbe
  -> Udp2rawAuthenticator.verify
  -> sendMtuAck(payload = acceptedDatagramBytes)

client ACK consume:
Udp2rawUpstream.decodeResponse
  -> handleMtuAck
  -> Udp2rawAuthenticator.verify
  -> Udp2rawMtuState.ack(seq, acceptedMtu, now)

normal request/response data write:
Udp2rawPayloadSupport.writeEncoded
  -> pre-check encoded bytes against currentMtu
  -> Sockets.writeUdp
  -> on MTU_EXCEEDED: Udp2rawMtuState.onWriteMtuDrop
```

当前实现意图：

- `SocketConfig.udpMtu` 仍作为硬上限。
- `UdpMtuProbeDatagramPacket` 允许 probe 超过本地 final MTU guard，由对端是否 ACK 来判断路径可达。
- ACK payload 回传实际收到的 datagram bytes，客户端用 `acceptedMtu` clamp 当前 MTU，避免 header 估算误差造成路径黑洞。

已发现的问题 / 风险：

1. **response 方向缺少协议级主动 probe**
   - client/upstream 侧会调度 `MTU_PROBE`，server entry 会 ACK。
   - server tunnel context 虽然有 `mtuState`，response 写回 peer 也使用 `context.currentMtu()`，但没有看到 server -> client 的主动 `MTU_PROBE` 调度。
   - 结果：request 方向可通过协议级 probe/ack 收敛，response 方向主要只靠本地写入 MTU_EXCEEDED 回调，无法发现 server -> client 路径黑洞，也无法主动向上探测。

2. **`udpMtu < 1200` 时状态机会把 currentMtu clamp 到 1200**
   - `SocketConfig.setUdpMtu` 允许任意 `>=0` 的值。
   - `new Udp2rawMtuState(initialMtu, side)` 固定传入 `MIN_MTU=1200`，如果 initialMtu 为 1000，最终 `minMtu=1200`、`maxMtu=1200`、`currentMtu=1200`。
   - 对配置了小 MTU 的 channel，`writeEncoded(..., state.currentMtu(), state)` 可能允许 1000~1200 之间的 datagram 进入 `Sockets.writeUdp`，最终被 final guard 丢弃；如果 channel 已安装 final guard，`Sockets.writeUdp` 会返回 ACCEPTED，状态机无法感知并下调。

3. **`MTU_ACK` payload 缺失时仍按 pendingMtu 成功 ACK**
   - 当前 `handleMtuAck` 在 `payload.readableBytes() < 4` 时把 acceptedMtu 置为 0。
   - `Udp2rawMtuState.ack(seq, 0, now)` 会把 0 解释为“使用 pendingMtu”。
   - 既然本次不考虑旧协议兼容性，协议级 ACK 应严格要求 4 字节 payload；缺失或长度异常应作为 bad ACK drop，而不是提升/验证 pending MTU。

4. **测试覆盖仍偏 request/client 方向**
   - 已有 test 覆盖 client 状态机、server ACK、write drop。
   - 仍缺少：server -> client response 方向 probe、client 收到 MTU_PROBE 并回 ACK、`udpMtu < 1200` hard cap、小 payload malformed ACK 拒绝、probe timeout 对 response state 的影响。

# 目标

1. 保证协议级 `MTU_PROBE/MTU_ACK` 对 request 和 response 两个方向都能工作。
2. 保证 `SocketConfig.udpMtu` 作为 hard cap 时不会被 `Udp2rawMtuState.MIN_MTU=1200` 放大。
3. 在不考虑旧协议兼容性的前提下，严格校验 `MTU_ACK` payload 格式。
4. 补齐测试，覆盖双向 probe/ack、低于 1200 的 hard cap、malformed ACK、final guard 与状态机交互。
5. 保持 JDK8 兼容，不引入新依赖，不做无关重构。

# 非目标

1. 不实现旧端兼容 fallback。
2. 不改变 `Sockets` final egress guard 的基本职责。
3. 不实现 UDP payload 分片/重组。
4. 不调整普通 UDP redundant/compression 的协议语义。
5. 不修改 secrets、token、证书、私钥。

# 设计方案

## 1. 补齐 response 方向协议级 probe/ack

建议把 MTU probe/ack 抽出为小 helper，避免 `Udp2rawUpstream` 与 `Udp2rawServerEntryHandler` 两边复制编码逻辑。

可选 helper：

```java
final class Udp2rawMtuProbeSupport {
    static ByteBuf encodeProbe(ByteBufAllocator alloc, byte[] secret,
                               long sessionHi, long sessionLo, long seq, int targetMtu);
    static ByteBuf encodeAck(ByteBufAllocator alloc, byte[] secret,
                             long sessionHi, long sessionLo, long seq, int acceptedMtu);
    static int readAckAcceptedMtu(ByteBuf payload);
}
```

server response 方向建议：

- `Udp2rawTunnelContext` 里保存 probe future 或由 `Udp2rawServerEntryManager` 管理调度。
- 第一次 `recordAuthSuccess(in.sender())` 后，server 已知 peer 地址，可以调度 server -> client `MTU_PROBE`。
- `Udp2rawServerEntryHandler` 或 `Udp2rawSession` 负责发送 server-side probe 到 peer。
- client `Udp2rawUpstream.decodeResponse` 增加处理 `MTU_PROBE`：
  - 校验 session。
  - 校验 auth tag。
  - 用收到的 datagram bytes 生成 `MTU_ACK` 回 server。
  - 不把 control frame 传给业务。
- server 收到 `MTU_ACK` 后更新 `context.mtuState.ack(seq, acceptedMtu, now)`。

注意：

- 如果一个 tunnel 多 peer，需要按 peer 维度或 session/peer 维度维护 response MTU state；如果设计上 tunnel 单 peer，则需在注释/测试里明确。
- ACK payload 必须签名，且验证时必须把 payload 纳入签名校验。

## 2. 修复 `initialMtu < MIN_MTU` 的 hard cap 放大

建议修改 `Udp2rawMtuState` 构造逻辑：

```java
int hardMax = initialMtu > 0 ? initialMtu : MAX_MTU;
int effectiveMin = Math.min(Math.max(576, minMtu), hardMax);
this.minMtu = effectiveMin;
this.maxMtu = Math.max(effectiveMin, maxMtu);
this.currentMtu = clamp(initialMtu, this.minMtu, this.maxMtu);
```

或者为 hard-cap 场景提供专门工厂：

```java
static Udp2rawMtuState fromHardCap(int hardCap, String side) {
    int min = hardCap > 0 ? Math.min(MIN_MTU, hardCap) : 0;
    return hardCap > 0 ? new Udp2rawMtuState(hardCap, min, hardCap, side) : null;
}
```

这样 `udpMtu=1000` 时 currentMtu 应为 1000，不应被抬高到 1200。

## 3. 严格校验 ACK payload

当前不需要兼容无 payload ACK，因此建议：

- `handleMtuAck` 中要求 `payload != null && payload.readableBytes() == 4`。
- 长度不是 4 时记录 `action=bad-ack-payload` 并 return。
- 删除或仅测试内部保留 `ack(seq, now)` 的兼容重载，生产路径不要使用 0 表示 accepted pending。

建议逻辑：

```java
if (payload == null || payload.readableBytes() != 4) {
    DiagnosticMetrics.record(METRIC_PREFIX + ".mtu.probe.count", 1D, "action=bad-ack-payload");
    return;
}
int acceptedMtu = payload.getInt(payload.readerIndex());
if (acceptedMtu <= 0) {
    // drop bad ack
}
```

## 4. 测试补齐

建议新增/补充：

- `Udp2rawMtuStateTest.initialBelowMinRespectsHardCap`
  - `new Udp2rawMtuState(1000, "client")` 应 current=1000，nextProbe.mtu=1000。
- `Udp2rawUpstreamTest.rejectsMtuAckWithoutAcceptedPayload`
  - signed ACK 但 payload 长度 0/3/5 都不能更新 state。
- `Udp2rawFixedEntryIntegrationTest.serverResponseDirectionMtuProbeAck`
  - server 发送 MTU_PROBE，client 回 MTU_ACK，server `context.mtuState` 收敛。
- `Udp2rawFixedEntryIntegrationTest.responseLargePacketUsesServerMtuState`
  - response encoded datagram 超过 server current MTU 时应 pre-drop，并记录状态不被错误放大。
- `UdpRedundantTest.dynamicMtuBelow1200DoesNotBypassFinalGuard`
  - current/hard cap 小于 1200 时，`writeEncoded` 预检应返回 MTU_EXCEEDED，而不是交给 final guard 静默丢弃。

# 修改文件列表

如进入修复阶段，预计修改：

- `rxlib/src/main/java/org/rx/net/socks/Udp2rawMtuState.java`
- `rxlib/src/main/java/org/rx/net/socks/upstream/Udp2rawUpstream.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawServerEntryHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawTunnelContext.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawSession.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawPayloadSupport.java`（视 final guard 交互修复方案而定）
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawMtuStateTest.java`
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawFixedEntryIntegrationTest.java`
- `rxlib/src/test/java/org/rx/net/socks/UdpRedundantTest.java`

本 review 计划文档：

- `docs/plan/Udp2rawAdaptiveMtuProbeAckReview-plan.md`

# 风险点

1. 双向 probe 后的状态归属风险
   - request 与 response 是不同方向路径；共用 state 会误判，分开 state 又要确保调度和 ACK 路由正确。

2. 多 peer / 多 session 风险
   - 如果 tunnel 下可能存在多个 peer，server response MTU 不应只有一个全局 `context.mtuState`。

3. ByteBuf 生命周期风险
   - ACK/probe payload 与 auth tag 都是 direct buffer；异常路径必须 release。
   - encode 成功交给 Netty 后不能提前 release。

4. final guard 交互风险
   - 已安装 final guard 时，部分 drop 通过 outbound handler metrics 可见，但 `Sockets.writeUdp` 返回值可能仍为 ACCEPTED；状态机不能依赖 final guard 后置反馈。

5. 测试稳定性风险
   - 真路径黑洞不可稳定复现，应使用低 hard cap、EmbeddedChannel、显式 probe/ack 包来构造可重复测试。

# 验证方案

进入代码修复阶段后建议执行：

```bash
mvn -pl rxlib -am "-Dmaven.test.skip=true" compile
mvn -pl rxlib -am "-DskipTests" test-compile
mvn -pl rxlib -am "-Dtest=Udp2rawMtuStateTest,Udp2rawFixedEntryIntegrationTest,UdpRedundantTest" "-Dmaven.test.skip=false" test
```

GitHub Actions：

- 触发 `jdk8-unit-tests.yml`。
- `test_classes` 建议：
  - `Udp2rawMtuStateTest`
  - `Udp2rawFixedEntryIntegrationTest`
  - `UdpRedundantTest`

CI 判断：

- 只有 workflow run `conclusion=success` 才能认为 CI 通过。
- 如果失败，先分类为编译失败、单元测试失败、JDK8 兼容失败、格式失败或环境问题，再按失败日志做最小修复。
