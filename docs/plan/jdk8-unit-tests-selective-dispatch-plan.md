# 背景

用户反馈当前 `jdk8-unit-tests.yml` 虽然用于 JDK8 单元测试验证，但 agent 触发时无法带上指定单元测试，导致默认跑全量测试太慢。

用户明确要求直接在 `master` 分支修改，不创建 agent 分支。

# 任务类型判断

本次归类为 **新需求 / CI 优化需求**。

原因：需要调整 GitHub Actions workflow 行为，让 agent 触发 JDK8 单测时可以选择性运行指定测试类，减少验证耗时。

# 当前上下文

已 review 文件：

- `.github/workflows/jdk8-unit-tests.yml`

当前 workflow 已有：

- `workflow_dispatch.inputs.test_classes`
- `Build and test` 步骤读取 `${{ inputs.test_classes }}`
- 非空时执行 `mvn ... -Dtest="$NORMALIZED_TEST_CLASSES" clean test`
- 为空时执行 `mvn ... clean test` 跑全量测试

现有问题不在 workflow 的手动输入定义，而在当前 agent 可用的 GitHub Action dispatch 工具没有 `inputs` 参数，只能传 `workflow_id` 与 `ref`。因此 agent 无法把 `test_classes` input 传给 workflow，最终一直使用默认空值并跑全量测试。

# 目标

1. 保留现有人工手动触发能力：用户在 GitHub UI 中仍可通过 `test_classes` 输入指定测试类。
2. 增加 agent 可控的 fallback：当 `test_classes` input 为空时，从 HEAD commit message 中解析测试类标记。
3. 让 agent 后续可以通过 commit message 携带测试类，例如：
   - `[test_classes=org.rx.core.ObjectPoolTest,org.rx.core.ObjectPoolRecycleOwnershipTest]`
   - `[tests=ObjectPoolTest,ObjectPoolRecycleOwnershipTest]`
4. 未指定测试类且 commit message 也没有标记时，继续跑全量测试，保持原行为。
5. 保持 JDK8 兼容，不改变 Maven 依赖和测试框架。

# 非目标

1. 不增加 push / pull_request 自动触发。
2. 不修改业务代码。
3. 不改变默认 Maven 测试参数。
4. 不删除已有 `test_classes` input。
5. 不引入额外第三方 action。
6. 不修改 release、snapshot、ssh deploy workflow。

# 设计方案

## 触发方式

继续使用：

```yaml
on:
  workflow_dispatch:
    inputs:
      reason:
      test_classes:
```

保留现有 `test_classes` 输入优先级。

## 解析优先级

在 `Build and test` 步骤中：

1. 先读取 `TEST_CLASSES=${{ inputs.test_classes }}`。
2. 如果为空，则读取当前 HEAD commit message：

```bash
COMMIT_MESSAGE="$(git log -1 --pretty=%B)"
```

3. 从 commit message 中解析以下标记之一：

```text
[test_classes=...]
[test_classes: ...]
[tests=...]
[tests: ...]
```

4. 提取到的值进入原有 normalize 流程：支持逗号、分号、空格、tab、换行分隔。
5. 仍然执行：

```bash
mvn $MAVEN_ARGS -Dtest="$NORMALIZED_TEST_CLASSES" clean test
```

## Agent 后续使用方式

后续代码 commit message 可以写成：

```text
fix(core): guard ObjectPool recycle ownership

[test_classes=org.rx.core.ObjectPoolTest,org.rx.core.ObjectPoolRecycleOwnershipTest]
```

之后 agent 调用 `actions_create_workflow_dispatch` 时即使无法传 inputs，workflow 也能从 HEAD commit message 提取测试类。

## 安全与兼容性

- 只解析方括号标记内的内容。
- 未解析到标记时保持全量测试行为。
- normalize 保持现有逻辑，避免 shell 参数被直接拼接成多个命令。
- 该方案不需要修改 agent 工具 schema。

# 修改文件列表

本次计划修改：

- `docs/plan/jdk8-unit-tests-selective-dispatch-plan.md`

后续代码修改：

- `.github/workflows/jdk8-unit-tests.yml`

# 风险点

## Workflow 语法风险

YAML 和 bash 引号需要谨慎，避免 `${{ ... }}`、shell 变量、sed 表达式相互冲突。

## Commit message 解析风险

如果 commit message 中出现多个 `[tests=...]` 标记，只解析第一个即可。后续应避免在无关提交中写类似标记。

## 测试范围风险

选择性测试会比全量测试更快，但覆盖范围更窄。最终合并或关键发布前仍建议全量跑一次。

## Agent 工具限制

当前 dispatch 工具仍无法传 `inputs`。该方案是通过 commit message fallback 绕过工具限制。

# 验证方案

1. 提交 workflow 修改后，在 `master` 触发 `JDK 8 Unit Tests`。
2. 如果 HEAD commit message 包含测试类标记，应看到日志：
   - `Resolved test classes from HEAD commit message`
   - `Running selected JDK 8 tests: ...`
3. Maven 命令应包含 `-Dtest="..."`。
4. 如果没有输入且没有 commit message 标记，应继续日志输出：
   - `Running all JDK 8 tests`
5. CI 结果必须以 workflow run `conclusion=success` 为准。
