# 背景

用户在 `rockylomo/rxlib` 的 `agent/udp2raw-adaptive-mtu-plan` 分支上说明：已根据上次 review 文档实现了服务端 response 方向主动 MTU_PROBE、客户端严格 4 字节 MTU_ACK、ACK 不再兼容空 payload、`udpMtu < 1200` 不再被状态机抬高到 1200，并把协议编码抽到 `Udp2rawMtuProbeSupport`。

本次任务是继续 review 最新实现。按仓库流程，本阶段只 review 并提交计划文档，不修改业务代码。

# 任务类型判断

本次属于 Review / 修复 / 优化需求。

原因：

- 用户明确要求“review 下”。
- 代码已实现，重点是检查设计落地、调用链、边界条件、ByteBuf 生命周期和测试覆盖。
- 未收到“开始修改代码”指令，因此不进入业务代码实现阶段。

# 当前上下文

已 review 的最新提交：

- 分支：`agent/udp2raw-adaptive-mtu-plan`
- 最新提交：`004aa34a23fb215afb57d937d6bd84f1717f1362`
- commit message：`feat(socks): implement bidirectional MTU probing and encapsulate helper utilities`

已 review 的文件：

- `rxlib/src/main/java/org/rx/net/socks/Udp2rawMtuProbeSupport.java`
  - 新增 probe / ack 编码 helper。
  - `encodeProbe(...)` 按目标 MTU 构造 padding，并对 probe payload 签名。
  - `encodeAck(...)` 写入 4 字节 accepted MTU，并把 ACK payload 纳入签名。
  - `readAckAcceptedMtu(...)` 严格要求 payload readable bytes 等于 4，且 accepted MTU 必须大于 0。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawMtuState.java`
  - 修复 `initialMtu < MIN_MTU` 时 current MTU 被抬高的问题。
  - 当前构造逻辑在 `maxMtu < MIN_MTU` 时会把 min clamp 到 max，从而尊重 hard cap。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawTunnelContext.java`
  - 新增 server 侧 response 方向 probe 调度。
  - `noteMtuPeer(...)` 在确认合法 peer 后记录 channel / peer，并启动定时 probe。
  - `sendMtuProbe(...)` 使用 `Udp2rawMtuProbeSupport.encodeProbe(...)` 发送 server-side MTU_PROBE。
- `rxlib/src/main/java/org/rx/net/socks/upstream/Udp2rawUpstream.java`
  - client 侧接收 `MTU_PROBE` 后校验 auth，并回 `MTU_ACK`。
  - client 侧处理 `MTU_ACK` 时改为严格读取 4 字节 accepted MTU。
  - 原有 client-side probe 编码迁移到 helper。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawServerEntryHandler.java`
  - server 侧 DATA 包在通过 peer/auth/seq 校验后调用 `noteMtuPeer(...)`。
  - server 侧接收 `MTU_ACK` 后校验 auth、严格读取 ACK payload，并更新 server `mtuState`。
  - server 侧收到 `MTU_PROBE` 后继续回 ACK，并触发 `noteMtuPeer(...)`。
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawFixedEntryIntegrationTest.java`
  - 新增 response 方向 probe/ack 集成测试。
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawMtuStateTest.java`
  - 新增低于 1200 的 hard cap 测试。
  - 新增 ACK payload 长度严格校验测试。
- `rxlib/src/test/java/org/rx/net/socks/UdpRedundantTest.java`
  - 新增 dynamic MTU below 1200 不绕过 final guard 的测试。

关键调用链：

```text
server 发现合法 peer:
Udp2rawServerEntryHandler.channelRead0
  -> DATA 通过 tunnel/session/auth/seq/peer 校验
  -> tunnel.noteMtuPeer(channel, sender)
  -> Udp2rawTunnelContext.scheduleMtuProbe
  -> runMtuProbe
  -> Udp2rawMtuProbeSupport.encodeProbe
  -> Sockets.writeUdp(UdpMtuProbeDatagramPacket)

client 收到 response 方向 probe:
Udp2rawUpstream.decodeResponse
  -> frame.type == MTU_PROBE
  -> handleMtuProbe
  -> verify auth with payload
  -> Udp2rawMtuProbeSupport.encodeAck
  -> Sockets.writeUdp(... flow=mtu-ack,side=client)

server 收到 response 方向 ack:
Udp2rawServerEntryHandler.channelRead0
  -> handleControlFrame
  -> handleMtuAck
  -> verify auth with payload
  -> readAckAcceptedMtu(payload)
  -> tunnel.mtuState.ack(seq, acceptedMtu, now)
```

当前实现意图：

- request 方向和 response 方向各自通过协议级 probe/ack 收敛 MTU。
- ACK 必须带 4 字节 accepted MTU，且该 payload 被签名保护。
- response 方向 probe 在确认合法 peer 后启动，避免未认证 peer 触发 probe。
- `udpMtu` 低于 1200 时，不再被 `MIN_MTU=1200` 抬高。

已确认改进点：

1. 上轮提到的 response 方向缺少协议级主动 probe，已补。
2. 上轮提到的 `udpMtu < 1200` 被状态机抬高，已修复。
3. 上轮提到的 ACK 空 payload 兼容路径，生产处理路径已改为严格 4 字节。
4. probe/ack 编码抽 helper 后，client/server 侧重复代码明显减少。
5. `encodeProbe` / `encodeAck` 的主要 ByteBuf 交接路径整体合理：payload 在交给 composite 后置空，auth tag 在 header copy 后 finally release。

# 目标

1. 检查双向 MTU_PROBE / MTU_ACK 主路径是否正确。
2. 检查 ACK payload 签名和严格长度校验是否正确落地。
3. 检查 server-side probe 调度生命周期是否有明显泄漏或重复调度风险。
4. 检查 `udpMtu < 1200` 修复是否仍存在自适应下调边界问题。
5. 补充 review 后续可选修复建议和测试增强点。

# 非目标

1. 不考虑旧端兼容性。
2. 不修改业务代码。
3. 不触发 CI。
4. 不调整 release、secrets、token、证书或私钥。
5. 不引入新依赖。

# 设计方案

本次 review 后建议的后续修复 / 优化点如下。

## 1. 加强 server 侧 MTU_ACK 的 peer guard / auth failure 处理

当前 `Udp2rawServerEntryHandler.handleMtuAck(...)` 会：

- 通过 session 找 tunnel。
- 校验 auth tag。
- 严格读取 4 字节 accepted MTU。
- 成功后更新 `tunnel.mtuState`。

但与 DATA / MTU_PROBE 路径相比，ACK 路径没有显式调用：

- `tunnel.isPeerBlocked(sender, now)`
- `tunnel.allowPeerPacket(sender, now)`
- auth 失败时 `tunnel.recordAuthFailure(sender, now)`

建议保持 control frame 安全策略一致：

```java
long now = System.currentTimeMillis();
if (tunnel.isPeerBlocked(in.sender(), now) || !tunnel.allowPeerPacket(in.sender(), now)) {
    recordDrop("peer-rate-limit");
    return;
}
if (!authOk) {
    tunnel.recordAuthFailure(in.sender(), now);
    ...
    return;
}
```

这样可以避免 bad ACK spam 绕过已有 peer guard / auth failure fuse。

## 2. response 方向 ACK 最好绑定最近 probe peer

当前 server-side `mtuState` 只有 pending seq，没有记录 pending probe 的 peer。`handleMtuAck(...)` 只要 tunnel/session/auth/seq 匹配，就会接受 ACK。

如果 tunnel 未来允许 peer rebind 或同一 tunnel 下多 peer，旧 peer 的 ACK 可能更新当前 peer 的 response MTU 状态。

建议可选增强：

- `Udp2rawTunnelContext` 记录 `pendingMtuProbePeer`。
- `sendMtuProbe(...)` 发送时绑定 peer。
- `handleMtuAck(...)` 成功更新前检查 `in.sender()` 是否等于 pending peer 或当前 `mtuProbePeer`。

如果设计上一个 tunnel 只允许一个 peer，也建议在注释中明确该假设。

## 3. 低 hard cap 场景仍不能继续下探

当前 `new Udp2rawMtuState(1000, "client")` 会得到：

- `minMtu = 1000`
- `maxMtu = 1000`
- `currentMtu = 1000`

它解决了“不要抬高到 1200”的问题，但也意味着当 hard cap 低于 1200 时，状态机不能继续下调到 900/800。

这可能是合理选择，因为 `udpMtu` 被视为硬上限而非探测上限；但如果目标是路径 MTU 自适应，建议保留更低下限：

```java
new Udp2rawMtuState(initialMtu, 576, initialMtu, side)
```

或显式增加配置：

- hard max：来自 `udpMtu`
- adaptive min：默认 `min(576, hardMax)` 或用户配置值

当前不是主路径 bug，但需要确认产品语义：`udpMtu < 1200` 时是“固定小 MTU”还是“允许继续自适应下探”。

## 4. 集成测试应验证 server 真正消费 ACK 后状态变化

`fixedEntrySendsResponseDirectionMtuProbeAndAcceptsAck` 已验证：

- server 会回 client 发起的 MTU_PROBE 的 ACK。
- server 会主动发 response 方向 MTU_PROBE。
- test 能构造并发送 ACK 回 server。

但当前测试没有断言 server 收到 ACK 后 `tunnel.mtuState` 是否真的更新，也没有让 ACK accepted MTU 与 pending MTU 不同来观察 clamp 行为。

建议增强测试：

- 让 client 对 server probe 回 `acceptedMtu = probeLength - 10`。
- 通过 package-private manager/context 或 metrics/后续 response drop 行为确认 server state 被 clamp。
- 至少验证 bad ACK payload 不会更新 state。

## 5. tiny MTU 小于 probe header 的边界可补测试

`Udp2rawMtuProbeSupport.encodeProbe(...)` 在 `targetMtu < headerBytes` 时会生成 header-only probe，实际 encoded datagram 仍大于 targetMtu。

这对正常 `udpMtu >= 576` 没问题，但当前构造允许 `initialMtu` 小到 1。

建议补一个测试或约束：

- 如果 `targetMtu < minimalProbeHeaderBytes`，不要发送 probe，或把 `currentMtu` 下限至少设为 minimal probe header bytes。
- metrics 记录 `action=probe-too-small`。

# 修改文件列表

如果后续进入修复阶段，预计会修改：

- `rxlib/src/main/java/org/rx/net/socks/Udp2rawServerEntryHandler.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawTunnelContext.java`
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawMtuState.java`（仅当决定支持低 hard cap 继续下探）
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawMtuProbeSupport.java`（仅当增加 tiny MTU 约束）
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawFixedEntryIntegrationTest.java`
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawMtuStateTest.java`

本 review 计划文档：

- `docs/plan/Udp2rawBidirectionalMtuProbeAckReview-plan.md`

# 风险点

1. peer guard 风险
   - ACK control frame 如果不纳入 peer guard，bad ACK spam 可能绕过已有 auth failure fuse。

2. peer rebind 风险
   - response MTU state 当前按 tunnel 维护，peer 变化时可能产生 pending ACK 与当前 peer 不一致的问题。

3. 低 MTU 语义风险
   - `udpMtu < 1200` 当前被视为 hard fixed cap，不会下探；如果用户预期自适应下探，需要调整 state min/max 语义。

4. 测试断言风险
   - 现有 response 方向集成测试验证了包能走通，但还没有强断言 server ACK consume 后状态变化。

5. 极小 MTU 风险
   - target MTU 小于 probe header 时实际 probe size 会大于 target，需要明确是否禁止这种配置或跳过 probe。

# 验证方案

本阶段仅提交 review 计划文档，不触发 CI。

如进入修复阶段，建议执行：

```bash
mvn -pl rxlib -am "-Dmaven.test.skip=true" compile
mvn -pl rxlib -am "-DskipTests" test-compile
mvn -pl rxlib -am "-Dtest=Udp2rawMtuStateTest,Udp2rawFixedEntryIntegrationTest,UdpRedundantTest" "-Dmaven.test.skip=false" test
```

GitHub Actions：

- 触发 `jdk8-unit-tests.yml`。
- `test_classes` 建议包含：
  - `Udp2rawMtuStateTest`
  - `Udp2rawFixedEntryIntegrationTest`
  - `UdpRedundantTest`

CI 判断：

- 只有 workflow run `conclusion=success` 才认为通过。
- 如果失败，先分类为编译失败、单测失败、JDK8 兼容失败、格式失败或环境问题，再按失败日志做最小修复。
