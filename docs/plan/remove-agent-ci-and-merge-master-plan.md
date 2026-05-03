# 背景

用户要求在 `RockyLOMO/rxlib` 仓库中删除：

- `.github/workflows/agent-ci.yml`
- `docs/plan/github-actions-manual-only-triggers-plan.md`

然后将当前 agent 分支的变更合并到 `master`。

本次延续 `agent/20260504-github-actions` 分支上的 GitHub Actions 配置工作。

# 任务类型判断

本次归类为 Review / 修复 / CI 配置清理需求。

原因：

- 用户明确要求删除已有文件，并将既有分支合入 `master`。
- 修改范围是 CI workflow 和计划文档清理，不涉及业务 Java 代码。
- 删除文件属于高风险操作，必须先记录计划再执行。

# 当前上下文

已 review 的内容：

- 当前 `master` 指向：`e978fe2f1f48305cb51dc7bcb790ddb12904db22`
- 当前 `agent/20260504-github-actions` 指向：`84eb3fcea2223c4d15edb59a315dc7ef95f70eb0`
- `agent/20260504-github-actions` 基于当前 `master` 向前提交，预计可以 fast-forward 合并。

当前分支里已有的相关变更：

- 新增手动触发 workflow：`.github/workflows/agent-commit-helper.yml`
- 新增手动触发 JDK 8 单测 workflow：`.github/workflows/jdk8-unit-tests.yml`
- `jdk8-unit-tests.yml` 支持 `test_classes` 输入。
- 两个新 workflow 都已改为仅 `workflow_dispatch`，不再由 `push` / `pull_request` 触发。

用户最新要求：

- 删除旧的 `.github/workflows/agent-ci.yml`，避免保留旧 Agent CI。
- 删除 `docs/plan/github-actions-manual-only-triggers-plan.md`。
- 合并到 `master`。

# 目标

1. 提交本计划文档。
2. 删除 `.github/workflows/agent-ci.yml`。
3. 删除 `docs/plan/github-actions-manual-only-triggers-plan.md`。
4. 保留新建的两个手动 workflow：
   - `.github/workflows/agent-commit-helper.yml`
   - `.github/workflows/jdk8-unit-tests.yml`
5. 将 `agent/20260504-github-actions` fast-forward 合并到 `master`。
6. 合并后尝试通过手动触发验证 workflow，而不是依赖 git 提交触发。

# 非目标

- 不删除 `agent/20260504-github-actions` 分支。
- 不删除其他 `docs/plan/*` 文档。
- 不修改 Java 源码和测试源码。
- 不修改 `pom.xml`。
- 不修改 release / snapshot / ssh deploy workflow。
- 不自动发布 release。
- 不修改 secrets、token、证书或私钥。

# 设计方案

## 删除旧 Agent CI

删除 `.github/workflows/agent-ci.yml`。

原因：

- 用户明确要求删除。
- 当前已有新的 `agent-commit-helper.yml` 和 `jdk8-unit-tests.yml`，并且都是手动触发。
- 删除旧 Agent CI 可以避免旧的 push 自动触发 workflow 与用户“非 git 提交触发”的要求冲突。

## 删除指定计划文档

删除 `docs/plan/github-actions-manual-only-triggers-plan.md`。

原因：

- 用户明确指定删除该计划文档。
- 本次会保留新的删除/合并计划文档，以满足变更记录要求。

## 合并策略

优先执行 fast-forward 合并：

- 确认 `master` 是 agent 分支祖先。
- 用 Git ref 更新将 `refs/heads/master` 指向 agent 分支最新 commit。
- 不使用 force。
- 不删除 agent 分支。

# 修改文件列表

预计新增：

- `docs/plan/remove-agent-ci-and-merge-master-plan.md`

预计删除：

- `.github/workflows/agent-ci.yml`
- `docs/plan/github-actions-manual-only-triggers-plan.md`

# 风险点

1. 删除旧 Agent CI 后，push 不再自动跑 `Agent CI`。
2. `JDK 8 Unit Tests` 已被设计为手动触发，因此合并到 `master` 后也不会因 push 自动运行。
3. 如果 `master` 在操作期间被别人推进，fast-forward 可能失败，需要重新比较并处理。
4. 删除计划文档会减少历史上下文，但 Git 历史仍保留此前提交内容。
5. `agent-commit-helper.yml` 作为新 workflow，可能只有合入默认分支后才稳定出现在 GitHub Actions 手动触发列表。

# 验证方案

1. 提交本计划文档，单独 commit。
2. 删除指定两个文件，单独 commit。
3. 读取分支文件确认：
   - `.github/workflows/agent-ci.yml` 不存在。
   - `docs/plan/github-actions-manual-only-triggers-plan.md` 不存在。
4. 比较 `master...agent/20260504-github-actions`，确认可 fast-forward。
5. 更新 `refs/heads/master` 到 agent 分支最新 commit，且 `force=false`。
6. 合并后尝试手动触发 `JDK 8 Unit Tests`，查询 run 状态。
7. 只有 workflow run `status=completed` 且 `conclusion=success` 才视为验证通过。
