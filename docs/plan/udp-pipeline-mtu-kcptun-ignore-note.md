# UDP pipeline MTU review 补充：暂不处理 kcptunClient 删除

# 背景

在 `agent/udp-pipeline-mtu-plan` 分支的 UDP pipeline MTU 实现 review 中，曾标记 `SocksConfig.kcptunClient` 字段删除为无关变更与潜在 API 兼容风险。

用户已明确指示：

> `SocksConfig.kcptunClient` 的无关删除仍未恢复，先忽略，添加到计划里。

因此本补充计划将该项从当前 UDP MTU 修复阻塞项中移出，仅作为已知风险记录。

# 任务类型判断

本次任务归类为 **Review / 计划补充需求**。

原因：

- 用户没有要求修改业务代码。
- 目标是调整 review/计划结论，将 `kcptunClient` 删除列为暂不处理项。
- 按当前仓库流程，本轮只提交计划文档，不进入代码修复阶段。

# 当前上下文

相关文档：

- `docs/plan/udp-pipeline-mtu-unified-entry-plan.md`
- `docs/plan/udp-pipeline-mtu-implementation-review.md`
- `docs/plan/udp-pipeline-mtu-implementation-rereview.md`

当前实现分支：

- `agent/udp-pipeline-mtu-plan`

上一轮 review 中剩余关注项包括：

1. `SocksConfig.kcptunClient` 无关删除。
2. `addUdpOptimizationHandlers(...)` public API 安装顺序说明。
3. GitHub Actions / JDK8 CI 尚未验证。

本补充后，第 1 项不再作为本轮 UDP MTU 实现的阻塞项。

# 目标

1. 明确 `SocksConfig.kcptunClient` 删除本轮暂不恢复。
2. 将该风险保留为已知兼容性风险，但不阻塞 UDP MTU / backpressure / compression / redundant 方案继续推进。
3. 后续 review 重点收敛到 final egress guard、handler 安装顺序说明、测试与 CI 验证。

# 非目标

1. 不恢复 `SocksConfig.kcptunClient` 字段。
2. 不修改 `SocksConfig.java` 或其他业务代码。
3. 不对 kcptun 相关功能做兼容性迁移设计。
4. 不触发 CI 或声称 CI 已通过。

# 设计方案

## 决策

`SocksConfig.kcptunClient` 删除在本轮 UDP MTU 任务中按以下方式处理：

- 状态：已知风险。
- 优先级：暂不处理。
- 是否阻塞 UDP MTU 合入：不阻塞。
- 后续处理方式：如后续发现外部 API 或配置依赖该字段，再单独开任务恢复或提供迁移说明。

## 当前仍建议保留的阻塞/收尾项

1. 补充 `addUdpOptimizationHandlers(...)` 的安装顺序约束说明，避免 public API 被误用。
2. 触发 `jdk8-unit-tests.yml`，并确认 workflow run `conclusion=success`。
3. 如 CI 失败，按失败类型做最小范围修复。

# 修改文件列表

本轮仅新增计划补充文档：

- `docs/plan/udp-pipeline-mtu-kcptun-ignore-note.md`

# 风险点

1. `SocksConfig.kcptunClient` 删除仍可能影响外部 getter/setter API 或历史配置，但本轮按用户指示暂不处理。
2. 如果未来有用户依赖该字段，需要单独评估是否恢复。
3. 当前 UDP MTU 实现仍需 CI 验证，不能因为该风险被忽略而认为整体已通过。

# 验证方案

本次仅提交文档，无业务代码变更，不触发 CI。

后续代码确认仍建议执行：

```bash
mvn -pl rxlib -am test -DskipTests
mvn -pl rxlib -am test -Dtest=SocketsTest,UdpPipelineMtuGuardTest
```

并触发 GitHub Actions：

- workflow：`jdk8-unit-tests.yml`
- branch：`agent/udp-pipeline-mtu-plan`
- 只有 `conclusion=success` 才能认为 CI 通过。
