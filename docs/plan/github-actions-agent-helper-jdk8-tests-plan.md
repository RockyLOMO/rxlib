# 背景

用户希望在 `RockyLOMO/rxlib` 仓库中创建 2 个 GitHub Actions：

1. 一个方便 agent 提交代码，尤其是处理大文件或多文件变更时使用。
2. 一个方便执行 JDK 8 单元测试验证。

本次任务属于 GitHub Actions / CI 配置新增需求，不涉及业务 Java 代码修改。

# 任务类型判断

本次归类为：新需求。

原因：

- 用户明确要求“创建 2 个 github action”。
- 需要新增或调整 `.github/workflows/**` 下的 workflow 配置。
- 目标是提升 agent 提交和 JDK 8 测试验证能力，而不是修复已有业务逻辑。

# 当前上下文

已扫描内容：

- 默认分支：`master`。
- 当前已有 workflows：
  - `.github/workflows/agent-ci.yml`
  - `.github/workflows/java-ssh-rssGateway.yml`
  - `.github/workflows/java-ssh-rssServer.yml`
  - `.github/workflows/maven-release.yml`
  - `.github/workflows/maven-snapshot.yml`
- 当前 `agent-ci.yml` 已存在，但使用 JDK 11 执行 `mvn -B -U clean test`。
- 根 `pom.xml` 是 Maven 多模块项目：
  - `rxlib`
  - `rxlib-x`
- 根 `pom.xml` 中已有：
  - `<java.version>1.8</java.version>`
  - `<maven.test.skip>true</maven.test.skip>`
  - Maven compiler plugin source/target 绑定 `${java.version}`。
- `AGENTS.md` 明确要求 Java 8 兼容，并且该项目是高性能网络/Netty 相关项目。

相关模块：

- `.github/workflows/**`
- 根 `pom.xml`
- Maven 多模块构建链路

需要遵守的现有风格：

- 不修改业务代码。
- 不改 public API。
- 不改 Maven 依赖版本。
- 不修改 release / snapshot 发布流程。
- 不修改 secrets、token、证书、私钥。
- 保持 JDK 8 兼容。

# 目标

1. 新增一个 agent 专用提交辅助 workflow。
   - 支持 `workflow_dispatch` 手动触发。
   - 目标用途是让 agent 在需要处理大文件、多文件变更、仓库内容打包或诊断提交能力时，有一个受控入口。
   - 默认不自动改业务代码、不自动提交未知内容。
   - 给 `GITHUB_TOKEN` 配置最小必要权限。
   - 提供 dry-run / diagnostic 风格能力，降低误提交风险。

2. 新增一个 JDK 8 单元测试 workflow。
   - 使用 Temurin JDK 8。
   - 支持 `workflow_dispatch`。
   - 支持 push 到 `agent/**` 分支时自动运行。
   - 支持 PR 到 `main` / `master` 时运行。
   - 使用 Maven cache。
   - 明确覆盖 `maven.test.skip`，确保执行测试。
   - 尽量只做单元测试/编译验证，不触发发布、签名、部署逻辑。

3. 保留现有 release / snapshot workflows 不变。

4. 代码实现完成后触发 GitHub Actions，并以当前 `agent/20260504-github-actions` 分支过滤查询结果。

# 非目标

- 不修改 Java 业务代码。
- 不调整 Netty、DNS、UDP、TCP、RPC 等运行时代码。
- 不升级 Maven 插件、依赖或 JDK 版本。
- 不修改 `maven-release.yml` / `maven-snapshot.yml` 的发布逻辑。
- 不引入第三方 GitHub Action 之外的重型依赖。
- 不处理 Git LFS 迁移或历史大文件清理。
- 不自动创建 PR、不自动 merge、不发布 release。

# 设计方案

## 1. Agent 提交辅助 workflow

建议新增文件：

- `.github/workflows/agent-commit-helper.yml`

设计要点：

- workflow 名称建议：`Agent Commit Helper`
- 触发方式：
  - `workflow_dispatch`
- inputs：
  - `reason`：本次辅助任务原因。
  - `ref`：要 checkout 的分支或 ref，默认 `master`。
  - `list_large_files`：是否列出仓库内较大的文件，默认 true。
  - `large_file_threshold_mb`：大文件阈值，默认 50 MB。
- permissions：
  - `contents: write`
  - 其他权限默认不授予。
- job 行为：
  - checkout 指定 ref，使用 `fetch-depth: 0`，便于 agent 检查历史或多文件状态。
  - 配置 git 用户为 GitHub Actions bot。
  - 打印当前分支、最新 commit、工作区状态。
  - 可选列出超过阈值的大文件，帮助判断是否需要 Contents API / Git Data API / LFS / 分块策略。
  - 不默认自动 commit/push，避免误提交。
  - 后续如需要自动提交，必须由用户明确给出具体文件和内容，或在后续计划中扩展。

资源和安全策略：

- 不读取或输出 secrets。
- 不推送未知文件。
- 不删除文件。
- 不自动修改 workflow 以外文件。
- 该 workflow 主要作为 agent 诊断/辅助环境，而不是“任意远程执行脚本”。

## 2. JDK 8 单元测试 workflow

建议新增文件：

- `.github/workflows/jdk8-unit-tests.yml`

设计要点：

- workflow 名称建议：`JDK 8 Unit Tests`
- 触发方式：
  - `workflow_dispatch`
  - `push` 到 `agent/**`
  - `pull_request` 到 `main` / `master`
- permissions：
  - `contents: read`
- job：
  - `runs-on: ubuntu-latest`
  - `actions/checkout@v4`
  - `actions/setup-java@v4`
    - `distribution: temurin`
    - `java-version: '8'`
    - `cache: maven`
  - 执行 Maven：
    - `mvn -B -U -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false clean test`
- 说明：
  - 根 `pom.xml` 中默认 `maven.test.skip=true`，所以 workflow 需要显式覆盖。
  - `gpg.skip=true` 避免验证阶段触发签名要求。
  - 使用 `clean test`，不执行 deploy/release。

## 3. 是否调整现有 `agent-ci.yml`

当前 `agent-ci.yml` 已存在且使用 JDK 11。为降低影响，初始方案不直接替换它，而是新增 JDK 8 专用 workflow。

如后续希望让“Agent CI”也改为 JDK 8，可在单独计划中修改 `.github/workflows/agent-ci.yml`，但本次不混入，以避免破坏现有 CI 语义。

# 修改文件列表

计划新增：

- `docs/plan/github-actions-agent-helper-jdk8-tests-plan.md`
- `.github/workflows/agent-commit-helper.yml`
- `.github/workflows/jdk8-unit-tests.yml`

预计不修改：

- `pom.xml`
- Java 源码
- `.github/workflows/maven-release.yml`
- `.github/workflows/maven-snapshot.yml`
- `.github/workflows/java-ssh-rssGateway.yml`
- `.github/workflows/java-ssh-rssServer.yml`

# 风险点

1. GitHub Actions runner 上 JDK 8 可用性风险：
   - 使用 `actions/setup-java@v4` + `temurin` 是常见组合，但仍依赖 GitHub-hosted runner 和 setup-java 下载能力。

2. Maven 测试失败风险：
   - 当前根 `pom.xml` 默认跳过测试，说明测试可能长期未在 CI 中强制执行。
   - 开启测试后可能暴露已有单测失败、环境依赖或不稳定测试。

3. GPG / 发布插件干扰风险：
   - `clean test` 理论上不执行发布，但部分插件可能绑定生命周期。
   - 通过 `-Dgpg.skip=true` 降低签名相关风险。

4. 大文件辅助 workflow 误用风险：
   - 如果设计为自动提交任意变更，会带来安全风险。
   - 本方案默认只诊断，不自动提交未知内容，后续如扩展必须单独计划。

5. 权限风险：
   - `agent-commit-helper.yml` 需要 `contents: write` 才能服务提交辅助用途。
   - 需保持 workflow 内容最小化，避免任意脚本输入。

6. 兼容性风险：
   - YAML 语法错误会导致 workflow 不可运行。
   - 需要提交后通过 GitHub Actions 验证。

# 验证方案

1. 提交计划文档后，等待用户明确同意进入实现阶段。
2. 实现阶段新增两个 workflow 文件，单独 commit。
3. 触发 GitHub Actions：
   - 优先触发已有 `Agent CI` 或新增 `JDK 8 Unit Tests`。
   - 对新增 `workflow_dispatch` workflow 手动触发验证。
   - 查询 workflow runs 时按 `agent/20260504-github-actions` 分支过滤。
4. 验证结论标准：
   - queued / in_progress / waiting 不算通过。
   - 只有 conclusion 为 `success` 才认为 CI 通过。
5. 如 CI 失败：
   - 区分 YAML 配置错误、JDK 8 环境问题、Maven 编译失败、单元测试失败或依赖下载失败。
   - 只修复与失败直接相关的 workflow 配置。
   - 再次 commit 并重新触发 CI。
