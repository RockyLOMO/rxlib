# 背景

用户要求 review `rxlib/src/main/java/org/rx/util` 下实现，排除 `rxlib/src/main/java/org/rx/util/rss`。随后补充：记得当前 `BeanMapper.java` 有一个赋值 bug，需要仔细确认。

本次按 Review / 修复前分析处理：先定位问题、提交计划文档；用户明确要求执行前，不修改业务代码。

# 任务类型判断

本次归类为 Review / 修复需求：

- review 现有 `org.rx.util` 实现，不是新增功能。
- 用户明确怀疑 `BeanMapper.java` 存在赋值 bug。
- 当前阶段只分析调用链、风险点和修复方案，不改业务代码。

# 当前上下文

已重点 review：

- `rxlib/src/main/java/org/rx/util/BeanMapper.java`
- `rxlib/src/main/java/org/rx/annotation/Mapping.java`
- `rxlib/src/test/java/org/rx/util/TestUtil.java`
- `rxlib/src/test/java/org/rx/test/PersonBean.java`
- `rxlib/src/test/java/org/rx/test/GirlBean.java`

已扫描目录：

- `rxlib/src/main/java/org/rx/util`
- `rxlib/src/main/java/org/rx/util/function`
- 排除：`rxlib/src/main/java/org/rx/util/rss`

关键调用链：

1. `BeanMapper#define(Class<T>)` 为 mapper 接口生成代理。
2. mapper 方法调用时，通过 `setMappings(...)` 把 method 上的 `@Mapping` 缓存到 `MapConfig.mappings`。
3. Bean -> Bean 使用 `BeanCopier.create(from, to, true)`，热路径在 converter 中按 target propertyName 找到 `@Mapping` 后调用 `processMapping(...)`。
4. 对于 target 中没有被 BeanCopier 同名 copy 到的 `@Mapping.target()`，copy 后会再循环补充赋值。
5. `processMapping(...)` 当前顺序是：ignore/null 判断 -> trim/format -> defaultValue -> converter -> 最后才读取 `mapping.source()`。

# 目标

- 确认 `BeanMapper.java` 中赋值 bug 的具体位置。
- 明确 `@Mapping.source()` 与 null 策略、defaultValue、trim、format、converter 的正确执行顺序。
- 给出最小修复方案和测试方案。
- 保持 JDK 8 兼容。
- 不修改业务代码，直到用户明确要求执行。

# 非目标

- 本阶段不修改 `BeanMapper.java`。
- 不重构整个 `org.rx.util` 包。
- 不替换 CGLIB / Byte Buddy。
- 不调整 Maven 依赖版本。
- 不修改 `rss` 包。
- 不发布 release。
- 不修改 secrets、token、证书、私钥。

# 设计方案

## 已定位的赋值 bug

`BeanMapper#processMapping(...)` 中，`@Mapping.source()` 的取值发生在处理链最后：

```java
if (mapping.converter() != BeanMapConverter.class) {
    sourceValue = Reflects.<BeanMapConverter>newInstance(mapping.converter()).convert(sourceValue, targetType, propertyName);
}
if (!Strings.isEmpty(mapping.source())) {
    Reflects.PropertyNode propertyNode = Reflects.getProperties(source.getClass())
            .firstOrDefault(p -> eq(mapping.source(), p.propertyName));
    if (propertyNode != null) {
        sourceValue = Reflects.invokeMethod(propertyNode.getter, source);
    }
}
return sourceValue;
```

这会导致显式 source 映射时使用错误的 sourceValue：

- `converter` 处理的是同名属性旧值，不是 `mapping.source()` 指定的值；随后 converter 结果又被 source 读取结果覆盖。
- `trim` / `format` 处理的是同名属性旧值或 null，随后被 source 读取结果覆盖，因此对 `@Mapping(source=..., target=..., trim/format=...)` 失效。
- `defaultValue` 处理的是同名属性旧值或 null，随后可能被 source 读取结果覆盖。
- `SKIP_NULL` / `Ignore` 判断发生在读取 `mapping.source()` 前，会按错误的 null 状态决定是否跳过赋值。

## 可复现场景

### 1. source/target 不同名 + trim/format 失效

```java
@Mapping(source = "name", target = "info", trim = true, format = "a%sb")
GirlBean toTarget(PersonBean source);
```

当前补充赋值路径传入 `processMapping(...)` 的 `sourceValue` 是 null。trim/format 阶段不会执行，最后才读取 `source.name`，所以 target.info 得到原始 name，而不是格式化后的值。

### 2. source/target 不同名 + SKIP_NULL 错误跳过

```java
@Mapping(source = "name", target = "info")
GirlBean toTarget(PersonBean source);
```

如果 flags 包含 `SKIP_NULL`，补充赋值路径传入 `sourceValue=null`，当前逻辑会在读取 `source.name` 之前直接返回 target 当前值，导致 `source.name` 非 null 也不会赋给 target.info。

### 3. 同名 target + source 指向另一个属性 + null 策略误判

```java
@Mapping(source = "index2", target = "index")
GirlBean toTarget(PersonBean source);
```

BeanCopier converter 进入 `target.index` 时，初始 `sourceValue` 是 `source.index`，不是 `source.index2`。因此 null 策略、defaultValue、converter 都可能基于错误属性判断。

### 4. source 属性写错时静默 fallback

如果 `mapping.source()` 找不到对应 source 属性，当前逻辑不会报错或警告，而是保留旧 `sourceValue`。这会隐藏注解拼写错误。是否改成 warn/throw 需要单独评估兼容性。

## 建议最小修复

把 `mapping.source()` 的读取提前到 `processMapping(...)` 前段，但保留 `ignore` 最高优先级：

1. 如果 `mapping.ignore()`，返回 target 当前值。
2. 如果 `mapping.source()` 非空，先从 source 读取指定属性并覆盖 `sourceValue`。
3. 再基于真实 `sourceValue` 判断 `SKIP_NULL` / `Ignore`。
4. 再处理 String 的 trim/format。
5. 再处理 `SetToDefault`。
6. 再执行 converter。
7. 返回 `sourceValue`。

伪代码：

```java
if (mapping.ignore()) {
    return readTargetValue(target, propertyName);
}
if (!Strings.isEmpty(mapping.source())) {
    PropertyNode propertyNode = Reflects.getProperties(source.getClass())
            .firstOrDefault(p -> eq(mapping.source(), p.propertyName));
    if (propertyNode != null) {
        sourceValue = Reflects.invokeMethod(propertyNode.getter, source);
    }
}
if (sourceValue == null && (skipNull || eq(mapping.nullValueStrategy(), BeanMapNullValueStrategy.Ignore))) {
    return readTargetValue(target, propertyName);
}
...
```

## 额外 review 风险点

- `config` 使用 `Map<Integer, MapConfig>`，key 是 `Objects.hash(from, to)`，理论上有 hash 碰撞风险；碰撞后会复用错误 BeanCopier。
- `MapConfig.flags` 是 from/to 级别缓存，不是 method 级别；同一 from/to 组合被不同 mapper method 或接口使用不同 flags 时可能互相污染。
- source 是 `Map` 时，当前路径只按 key 同名 setter 赋值，完全绕过 `@Mapping`、converter、trim、format、defaultValue 和 null 策略；如果 mapper method 支持 Map source，行为和 Bean source 不一致。
- `function` 包当前主要是函数式接口定义，未发现和本次 BeanMapper 赋值 bug 直接相关问题。

# 修改文件列表

本阶段仅新增计划文档：

- `docs/plan/BeanMapper-assignment-bug-review-plan.md`

如果用户后续要求执行，预计修改：

- `rxlib/src/main/java/org/rx/util/BeanMapper.java`
- `rxlib/src/main/java/org/rx/core/Reflects.java`
- `rxlib/src/test/java/org/rx/core/ReflectsCompatibilityTest.java`
- `rxlib/src/test/java/org/rx/util/BeanMapperAssignmentTest.java`

# 风险点

- 行为兼容风险：显式 `@Mapping.source()` 的处理结果会变化，但这是修复错误语义。
- converter 风险：converter 将收到真实 source 属性值，而不是同名属性旧值。
- null 策略风险：`SKIP_NULL`、`Ignore`、`SetToDefault` 会以真实 source 属性为准，需要测试覆盖。
- source 属性缺失风险：是否从静默 fallback 改为 warn/throw 需要单独决策。
- 缓存风险：如果同时修复 `Objects.hash` 或 method flags，需要并发和回归测试。
- JDK 8 风险：实现不能使用 JDK 9+ API。

# 验证方案

建议新增或扩展测试：

1. `@Mapping(source = "name", target = "info", trim = true, format = "a%sb")`，source.name 带空格，断言 target.info 为格式化后的值。
2. `@Mapping(source = "name", target = "info")` + `SKIP_NULL`，source.name 非 null，断言 target.info 被覆盖。
3. `@Mapping(source = "index2", target = "index")` + null 策略，断言 target.index 来自 source.index2。
4. `@Mapping(source = "name", target = "info", converter = XxxConverter.class)`，断言 converter 收到 source.name，且 converter 结果不被覆盖。
5. 原有 `defineMapBean` 和 `normalMapBean` 测试继续通过。

本地验证命令：

```bash
mvn -pl rxlib -Dtest=org.rx.util.TestUtil test
```

如新增独立测试类：

```bash
mvn -pl rxlib -Dtest=org.rx.util.BeanMapperTest test
```

代码 commit 后触发 `jdk8-unit-tests.yml`，`test_classes` 包含相关测试类；只在 workflow run `conclusion=success` 后认为 CI 通过。

# 执行记录（2026-05-06）

## 已执行

- 修改 `rxlib/src/main/java/org/rx/util/BeanMapper.java`：
  - `mapping.ignore()` 仍保持最高优先级。
  - `mapping.source()` 提前读取，后续 `SKIP_NULL` / `Ignore` / `trim` / `format` / `defaultValue` / `converter` 均基于显式 source 的真实值执行。
- 新增 `rxlib/src/test/java/org/rx/util/BeanMapperAssignmentTest.java`：
  - 覆盖 `source != target` 时 `trim + format` 生效。
  - 覆盖 `SKIP_NULL` 先读取显式 source 后再判断。
  - 覆盖同名 target 但 source 指向其他属性时，`Ignore` null 策略不再被旧同名值误导。
  - 覆盖 converter 接收显式 source 值，且 converter 结果不再被后置 source 读取覆盖。
- 追加修复首轮回归暴露的问题：
  - `Reflects.changeType` 优先使用已注册转换器，并补齐 `Number -> BigDecimal`，其中 `Decimal -> BigDecimal` 直接取 `Decimal.getValue()`。
  - 普通 Bean copy 遇到未显式 `@Mapping` 的不兼容同名属性时，`LOG_ON_MISS_MAPPING` 跳过并保留目标旧值；显式 `@Mapping` 或 `THROW_ON_MISS_MAPPING` 仍抛错。
  - `convertFromObjectString` 使用完整分隔串 `", "`，避免日期时间值中的空格被误拆。

## 不认可 / 未执行项

- 未执行 source 属性缺失时从静默 fallback 改为 warn/throw：
  - 原计划也标注该项需要单独评估兼容性。
  - 本次保持原行为，避免破坏现有注解容错语义。
- 未执行 `Objects.hash(from, to)` 缓存碰撞、`MapConfig.flags` method 级隔离、Map source 注解支持等额外风险修复：
  - 这些属于计划中的额外 review 风险，不是本次赋值 bug 的最小修复面。

## 验证结果

- 通过：`mvn -pl rxlib '-Dtest=org.rx.util.BeanMapperAssignmentTest' test`
  - 初始 source 赋值回归 Tests run: 4, Failures: 0, Errors: 0, Skipped: 0。
- 首轮未通过：`mvn -pl rxlib '-Dtest=org.rx.util.TestUtil#defineMapBean+normalMapBean' test`
  - `Decimal -> BigDecimal` 是 `Reflects.changeType` 注册转换器未优先使用的问题，已修复。
  - `DateTime -> int` 不应补默认有损转换，改为 BeanMapper 在非显式 `@Mapping` 且仅 `LOG_ON_MISS_MAPPING` 时跳过不兼容同名属性；`THROW_ON_MISS_MAPPING` 仍抛错。
  - `convertFromObjectString` 后续暴露 `Strings.split(", ")` 误用字符集合分隔的问题，已改为完整分隔串。
- 最终通过：`mvn -pl rxlib '-Dtest=org.rx.core.ReflectsCompatibilityTest,org.rx.util.BeanMapperAssignmentTest,org.rx.util.TestUtil#defineMapBean+normalMapBean' test`
  - Tests run: 13, Failures: 0, Errors: 0, Skipped: 0。
