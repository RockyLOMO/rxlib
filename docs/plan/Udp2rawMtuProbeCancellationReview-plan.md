# 背景

用户在 `rockylomo/rxlib` 的 `agent/udp2raw-adaptive-mtu-plan` 分支上说明代码已再次更新，并要求 review。

本次 review 的重点是上一轮剩余低风险建议是否已落地：

- local write drop 后是否立即取消 pending probe。
- server response 方向 pending peer 是否不再在 write 前过早标记。
- tiny MTU 是否保证不进入无法编码 probe 的状态。
- 取消 pending probe 后的 late ACK 是否被忽略。
- 测试是否覆盖这些行为。

本阶段只 review 并提交计划文档，不修改业务代码。

# 任务类型判断

本次属于 Review / 修复 / 优化需求。

原因：

- 用户明确要求“再 review 下”。
- 代码已经在 feature 分支实现，需要检查最新改动是否解决上轮 review 风险。
- 未收到“开始修改代码”指令，因此不进入业务代码实现阶段。

# 当前上下文

review 分支：

- `agent/udp2raw-adaptive-mtu-plan`

review 基准提交：

- `943e60e4a769d6775cf1274354c03ff7caaaea17`
- commit message：`feat(socks): support immediate state cancellation on local write drops and guarantee minimum MTU constraints`

已 review 的文件：

- `rxlib/src/main/java/org/rx/net/socks/Udp2rawMtuState.java`
  - `effectiveMax` 现在至少为 `Udp2rawMtuProbeSupport.MIN_PROBE_DATAGRAM_BYTES`。
  - `minMtu` 也至少为 `MIN_PROBE_DATAGRAM_BYTES`。
  - 新增 `cancelPendingProbe(long seq, long now)`，用于取消已进入 pending 的 probe，并把下一次 probe 时间提前到 retry window。
- `rxlib/src/main/java/org/rx/net/socks/Udp2rawTunnelContext.java`
  - server-side response probe 在 `Sockets.writeUdp(...) == ACCEPTED` 后才 `markPendingMtuProbePeer(peer)`。
  - local write drop 或 encode exception 时调用 `cancelMtuProbe(probe.seq)`。
  - `cancelMtuProbe(...)` 会清理 `pendingMtuProbePeer`，并调用 `mtuState.cancelPendingProbe(...)`。
- `rxlib/src/main/java/org/rx/net/socks/upstream/Udp2rawUpstream.java`
  - client-side request probe 在 local write drop 或 encode exception 时调用 `state.mtuState.cancelPendingProbe(...)`。
- `rxlib/src/test/java/org/rx/net/socks/Udp2rawMtuStateTest.java`
  - 新增 `cancelPendingProbeDoesNotLowerOrAcceptLateAck`。
  - 新增 `tinyMtuFloorsToMinimumProbeFrame`。
  - 原 tiny MTU 测试从“跳过 probe”改为“floor 到最小 probe frame 并能编码等长 probe”。

关键调用链：

```text
server response probe local write failure:
Udp2rawTunnelContext.runMtuProbe
  -> mtuState.nextProbe(now) creates pending seq
  -> sendMtuProbe(...)
  -> Sockets.writeUdp(...)
  -> result != ACCEPTED
  -> cancelMtuProbe(probe.seq)
  -> clearPendingMtuProbePeer()
  -> mtuState.cancelPendingProbe(seq, now)

client request probe local write failure:
Udp2rawUpstream.sendMtuProbe
  -> Sockets.writeUdp(...)
  -> result != ACCEPTED or exception
  -> state.mtuState.cancelPendingProbe(probe.seq, now)
```

当前实现意图：

- local write/socket delivery failure 不再等待 2 秒 ACK timeout 才恢复状态机。
- server response pending peer 只在 probe 被本地接受发送后标记。
- tiny MTU 配置不再产生“无法 probe 的 current MTU”，而是 floor 到最小可编码 probe datagram。
- cancel 后的 late ACK 因 pending seq 已清空，不会更新 MTU state。

# 目标

1. 确认上轮 pending peer 标记时机问题已解决。
2. 确认 local write drop 能立即取消 pending probe。
3. 确认 tiny MTU 下限语义明确且不会产生 oversized probe。
4. 确认新增测试覆盖 late ACK 和 tiny MTU 边界。
5. 输出剩余低风险建议。

# 非目标

1. 不修改业务代码。
2. 不触发 CI。
3. 不考虑旧协议兼容性。
4. 不实现 UDP payload 分片/重组。
5. 不修改 release、secrets、token、证书或私钥。

# 设计方案

本次 review 结论：主路径实现合理，没有看到 blocker。

## 已确认通过的点

1. pending peer 不再过早标记

server-side `sendMtuProbe(...)` 已改为：

```java
Sockets.UdpWriteResult result = Sockets.writeUdp(...);
if (result == Sockets.UdpWriteResult.ACCEPTED) {
    markPendingMtuProbePeer(peer);
} else {
    cancelMtuProbe(probe.seq);
}
```

这解决了上轮指出的“writeUdp 前标记 pending peer，local drop 后仍保留 pending peer”的问题。

2. local write drop 会立即取消 pending probe

`Udp2rawMtuState.cancelPendingProbe(...)` 会清理：

- `pendingSeq`
- `pendingMtu`
- `pendingDeadlineMillis`

并把 `nextProbeAtMillis` 提前到 retry window。这样 local write drop 不再需要等 ACK timeout 才能继续调度。

3. late ACK 被忽略

测试 `cancelPendingProbeDoesNotLowerOrAcceptLateAck` 已覆盖：

- 创建 probe。
- cancel pending probe。
- 同 seq late ACK 返回 false。
- current MTU 不变化。

4. tiny MTU 语义更明确

构造器现在保证：

```text
minMtu >= MIN_PROBE_DATAGRAM_BYTES
maxMtu >= MIN_PROBE_DATAGRAM_BYTES
currentMtu >= MIN_PROBE_DATAGRAM_BYTES
```

因此不会再出现 current MTU 小于 control probe 最小可编码大小的状态。

`tinyMtuFloorsToMinimumProbeFrame` 已验证：

- `new Udp2rawMtuState(40, 1, 40, "client")` 会 floor 到 `MIN_PROBE_DATAGRAM_BYTES`。
- `nextProbe(...)` 能生成 probe。
- `encodeProbe(...)` 输出长度等于最小 probe datagram。
- 低于 `MIN_PROBE_DATAGRAM_BYTES` 的 encode target 会抛异常。

## 剩余建议

### 1. tiny hard cap 被抬高的语义已写入代码注释

这轮把 tiny MTU 从“低于最小 probe 时不发 probe”改为“floor 到最小 probe frame”。这对协议级 probe 是合理的，但语义上意味着如果用户传入极小 hard cap，例如 `40`，状态机会把 current/max 提升到最小 control frame size。

已在 `Udp2rawMtuState` 构造器附近加注释说明：

- adaptive MTU 不支持低于 udp2raw MTU_PROBE 最小 datagram 的值。
- 极小配置会被 floor 到 `MIN_PROBE_DATAGRAM_BYTES`，否则协议级 probe 无法工作。

这不是 blocker，但能避免以后误解“udpMtu hard cap 永不放大”。

### 2. client-side probe 没有 pending peer，当前可接受

client-side request probe 只发往 `state.udpTransportAddress`，ACK 由 `TunnelState` 和 seq 校验。相比 server response 方向，client 侧没有多 peer 问题。

设计结论：当前可接受，不改代码；如果后续支持 client 侧 transport rebind 或多 upstream transport，再考虑绑定 pending transport address。

### 3. local write ACCEPTED 仍不等于网络真实发送成功

`Sockets.writeUdp(...) == ACCEPTED` 只能表示本地 write path 接受，不代表 UDP 包一定到达网络或对端。后续仍依赖 ACK timeout 处理真实丢包，这是 UDP 语义下合理的。

设计结论：这里不改业务代码，已在自适应 MTU 总设计中明确 `ACCEPTED` 的语义是 local accepted。

### 4. 仍建议实际跑 CI

本次改动包含测试、构造器边界和 Netty buffer 编码路径。review 没看到明显编译问题，但仍需要 GitHub Actions / Maven 验证。

# 修改文件列表

本 review 阶段未修改业务代码，仅新增文档：

- `docs/plan/Udp2rawMtuProbeCancellationReview-plan.md`

如果后续做文档/注释增强，预计可能涉及：

- `rxlib/src/main/java/org/rx/net/socks/Udp2rawMtuState.java`
- `docs/plan/*` 或相关设计文档

# 风险点

1. tiny hard cap 语义风险
   - 极小 MTU 会被 floor 到最小 probe frame，理论上与“hard cap 不放大”的直觉不同；但这是为了保证 control frame 可编码。

2. async write 语义风险
   - local ACCEPTED 不代表网络送达，仍需 ACK timeout 兜底。

3. CI 风险
   - 未在本阶段执行编译和单测；需要 CI 确认 JDK8 编译和测试稳定性。

# 验证方案

本阶段仅提交 review 计划文档，不触发 CI。

如果后续进入修复或验证阶段，建议执行：

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

- 只有 workflow run `conclusion=success` 才认为 CI 通过。
- 如果失败，先分类为编译失败、单测失败、JDK8 兼容失败、格式失败或环境问题，再按失败日志做最小修复。
