# 背景

用户希望 `RockyLOMO/rxlib` 仓库中的 `jdk8-unit-tests.yml` 支持传入“相关测试类名列表”。当列表为空时继续执行全部单元测试；当列表非空时只执行传入的测试类，方便 agent 在修复某一块代码时先做更小范围的 JDK 8 验证。

# 任务类型判断

本次归类为新需求。

原因是需要扩展现有 GitHub Actions workflow 的输入参数和 Maven 测试执行逻辑，属于 CI 配置功能增强，不涉及业务 Java 代码修复或重构。

# 当前上下文

已 review 的文件：

- `.github/workflows/jdk8-unit-tests.yml`
- 根 `pom.xml`

当前实现意图：

- `jdk8-unit-tests.yml` 当前支持 `workflow_dispatch`、`agent/**` push、PR 到 `main/master`。
- 当前固定执行：
  `mvn -B -U -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false clean test`
- 根 `pom.xml` 默认 `maven.test.skip=true`，因此 workflow 已显式覆盖为 `false`。
- 项目是 Maven 多模块项目，Java 版本为 1.8。

需要遵守的现有风格：

- 不修改业务代码。
- 不修改 Maven 依赖版本。
- 不触碰 release/snapshot 发布 workflow。
- 保持 JDK 8 验证语义。

# 目标

1. 为 `JDK 8 Unit Tests` workflow 增加可选输入 `test_classes`。
2. `test_classes` 为空时执行全部单元测试。
3. `test_classes` 非空时转换为 Maven Surefire `-Dtest=...` 参数，只执行指定测试类。
4. 支持常见输入格式：逗号、空格、分号、换行分隔。
5. 保持 push / PR 自动触发时默认执行全量测试。

# 非目标

- 不修改 Java 源码和测试源码。
- 不修改 `pom.xml`。
- 不修改发布、部署、snapshot、release workflow。
- 不新增第三方 action 或重型依赖。
- 不处理 Git LFS 或历史大文件迁移。

# 设计方案

在 `.github/workflows/jdk8-unit-tests.yml` 的 `workflow_dispatch.inputs` 下新增：

- `test_classes`
  - required: false
  - default: ""
  - description 说明可传 `FooTest,BarTest` 或换行列表。

将 “Build and test” 步骤改为 shell 脚本：

1. 定义基础 Maven 命令：
   `mvn -B -U -Dgpg.skip=true -Dmaven.test.skip=false -DskipTests=false`
2. 从 `${{ inputs.test_classes }}` 读取用户输入。
3. 用 shell 将换行、空格、分号统一转换成逗号。
4. 去掉首尾和重复逗号。
5. 如果最终列表为空：
   - 执行 `clean test`
6. 如果最终列表非空：
   - 执行 `-Dtest="$NORMALIZED_TEST_CLASSES" clean test`

异常处理：

- workflow_dispatch 输入为空时不传 `-Dtest`，避免 Maven Surefire 因空 test pattern 失败。
- 仅对输入做分隔符规范化，不尝试解析 Java 包名或文件路径。
- 用户如果传入不存在的测试类，失败交给 Maven/Surefire 暴露。

# 修改文件列表

预计修改或新增：

- `docs/plan/jdk8-unit-tests-test-classes-input-plan.md`
- `.github/workflows/jdk8-unit-tests.yml`

# 风险点

1. Maven Surefire 匹配风险：
   - `-Dtest` 对类名、通配符和方法名有特定语义，用户传入不匹配的名称会导致测试失败。

2. 输入格式风险：
   - 本方案支持逗号、空格、分号、换行；不支持带空格的复杂 shell 表达式。

3. 自动触发风险：
   - push / PR 触发没有输入参数，必须保持全量测试路径不受影响。

4. YAML 语法风险：
   - 多行 shell 与 GitHub expression 混用需要避免缩进和引号错误。

5. CI 运行风险：
   - 当前仓库已有测试可能在 JDK 8 环境下暴露失败；本次只处理 workflow 逻辑，不修改业务测试。

# 验证方案

1. 提交计划文档，单独 commit。
2. 修改 `.github/workflows/jdk8-unit-tests.yml`，单独 commit。
3. 触发 `JDK 8 Unit Tests` workflow。
4. 查询当前 `agent/20260504-github-actions` 分支的 workflow run。
5. 只有 `status=completed` 且 `conclusion=success` 才视为 CI 通过。
6. 如果 CI 失败，优先判断是否为 YAML 配置错误、JDK 8 环境问题、Maven 编译失败或单元测试失败。
