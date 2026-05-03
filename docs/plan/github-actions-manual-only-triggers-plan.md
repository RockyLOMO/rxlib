# 背景

用户要求前面创建的两个 GitHub Actions 都必须是“非 git 提交触发”，即不因为 push、pull_request 等代码提交事件自动运行，只能通过手动触发等非提交事件运行。

本次涉及的两个 workflow：

- `.github/workflows/agent-commit-helper.yml`
- `.github/workflows/jdk8-unit-tests.yml`

# 任务类型判断

本次归类为新需求 / CI 配置调整需求。

原因：

- 用户明确提出对已有 workflow 触发方式的新增约束。
- 修改范围仅限 GitHub Actions 配置，不涉及业务 Java 代码。
- 需要保持 JDK 8 单元测试 workflow 已有 `test_classes` 输入能力，同时移除 git 提交触发。

# 当前上下文

已 review 的文件：

- `.github/workflows/agent-commit-helper.yml`
- `.github/workflows/jdk8-unit-tests.yml`

当前实现意图：

- `agent-commit-helper.yml` 当前已经只使用 `workflow_dispatch`，本身不包含 `push` / `pull_request`。
- `jdk8-unit-tests.yml` 当前同时包含：
  - `workflow_dispatch`
  - `push` 到 `agent/**`
  - `pull_request` 到 `main` / `master`
- 用户最新要求两个 action 都不应该由 git 提交触发，因此 `jdk8-unit-tests.yml` 需要移除 `push` 和 `pull_request`。
- 为了让 `agent-commit-helper.yml` 的设计意图更明确，计划增加注释声明该 workflow 仅允许手动触发。

# 目标

1. 两个 workflow 都只保留非 git 提交触发方式。
2. 保留 `workflow_dispatch` 手动触发。
3. 保留 `jdk8-unit-tests.yml` 的 `test_classes` 输入能力。
4. 保留 JDK 8、Maven cache、`-Dgpg.skip=true`、`maven.test.skip=false`、`skipTests=false` 等测试参数。
5. 不修改业务代码、不修改 Maven 依赖、不修改发布 workflow。

# 非目标

- 不修改 Java 源码和测试源码。
- 不修改 `pom.xml`。
- 不修改 release / snapshot / ssh deploy workflow。
- 不引入第三方 action。
- 不新增 push、pull_request、schedule 等自动触发。
- 不处理 Git LFS 或大文件历史。

# 设计方案

## Agent Commit Helper

保持：

```yaml
on:
  workflow_dispatch:
```

增加注释说明：

```yaml
# Manual-only workflow. Do not add push or pull_request triggers.
```

该 workflow 仍作为手动诊断 / agent 辅助入口，不因代码提交自动运行。

## JDK 8 Unit Tests

从 `on:` 下删除：

```yaml
push:
  branches:
    - "agent/**"
pull_request:
  branches:
    - main
    - master
```

仅保留：

```yaml
on:
  workflow_dispatch:
    inputs:
      reason:
      test_classes:
```

这样：

- 用户在 Actions UI 手动运行时，可以传 `test_classes`。
- 不传 `test_classes` 时跑全量测试。
- 传入 `test_classes` 时通过 `-Dtest=...` 只跑指定测试类。
- 后续代码 push 不会自动触发该 workflow。

# 修改文件列表

预计修改：

- `docs/plan/github-actions-manual-only-triggers-plan.md`
- `.github/workflows/agent-commit-helper.yml`
- `.github/workflows/jdk8-unit-tests.yml`

# 风险点

1. CI 自动验证减少：
   - 移除 push / PR 后，提交代码不会自动运行 JDK 8 单元测试，需要手动触发。

2. PR 检查风险：
   - 如果未来依赖该 workflow 作为 PR required check，移除 PR 触发会导致 PR 不再自动产生该 check。

3. 手动触发可见性：
   - 新 workflow 在非默认分支上，GitHub UI / dispatch API 有时需要 workflow 先进入默认分支后才稳定显示。

4. 输入路径风险：
   - `test_classes` 仍依赖 Maven Surefire 的 `-Dtest` 语义，用户传入错误类名会导致测试失败。

# 验证方案

1. 提交本计划文档，单独 commit。
2. 修改两个 workflow，代码 commit 与计划 commit 分离。
3. 查询分支上的 workflow 内容，确认：
   - `agent-commit-helper.yml` 只有 `workflow_dispatch`。
   - `jdk8-unit-tests.yml` 只有 `workflow_dispatch`。
   - 两个文件都不再包含 `push:` 或 `pull_request:`。
4. 手动触发 `JDK 8 Unit Tests` 验证 workflow 可运行。
5. 只有 workflow run `status=completed` 且 `conclusion=success` 才认为 CI 通过。
