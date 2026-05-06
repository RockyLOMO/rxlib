# BeanMapper 综合评审与赋值 Bug 修复方案 (BeanMapper Comprehensive Review & Bug Fix)

# 背景

在 `rxlib` 的 `org.rx.util` 模块中，`BeanMapper.java` 承担了高频的对象属性映射、复制和转换工作。为了提升整体设计的鲁棒性并确认其在高性能网络与复杂业务场景下的效率，本次重点完成了两大维度的主题审查：
1. **BeanMapper 赋值 Bug**：修复在自定义注解 `@Mapping` 指定 `source` 与其他映射修饰符（如 `trim`、`format`、`defaultValue`、`converter`、`skipNull` 等）组合时存在的赋值覆盖与误判 Bug。
2. **CGLIB 与 Byte Buddy 技术选型分析**：评估在 JDK 8 和 JDK 17 环境下，`BeanMapper` 使用的 Spring repackaged CGLIB `BeanCopier` 相比于 Byte Buddy 在首次生成成本、热路径吞吐、类定义加载等层面的表现及迁移可行性。

---

# 当前上下文

已审查的核心源文件与测试资源：
- **主逻辑代码**：
  - [BeanMapper.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/util/BeanMapper.java) — 实体属性复制缓存、高级映射功能。
  - [Reflects.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/core/Reflects.java) — 基础类信息解析、通用类型转换及反射池。
- **配置与注解**：
  - [Mapping.java](file:///d:/projs_r/rxlib/rxlib/src/main/java/org/rx/annotation/Mapping.java) — 自定义映射配置注解。
- **测试/验证集**：
  - [TestUtil.java](file:///d:/projs_r/rxlib/rxlib/src/test/java/org/rx/util/TestUtil.java) 
  - [ReflectsCompatibilityTest.java](file:///d:/projs_r/rxlib/rxlib/src/test/java/org/rx/core/ReflectsCompatibilityTest.java)
  - [BeanMapperAssignmentTest.java](file:///d:/projs_r/rxlib/rxlib/src/test/java/org/rx/util/BeanMapperAssignmentTest.java)（新增）

---

# 深度技术剖析与方案设计

## 1. BeanMapper 赋值 Bug 深度分析与修复

### 问题根源
在 `BeanMapper#processMapping(...)` 原实现中，`@Mapping.source()` 属性值的提取与赋权发生在了整条处理链的**最后阶段**：
```java
// 原逻辑顺序
if (mapping.converter() != BeanMapConverter.class) {
    sourceValue = Reflects.<BeanMapConverter>newInstance(mapping.converter()).convert(sourceValue, targetType, propertyName);
}
if (!Strings.isEmpty(mapping.source())) {
    Reflects.PropertyNode propertyNode = Reflects.getProperties(source.getClass()).firstOrDefault(p -> eq(mapping.source(), p.propertyName));
    if (propertyNode != null) {
        sourceValue = Reflects.invokeMethod(propertyNode.getter, source);
    }
}
return sourceValue;
```

这会导致在显式指定非同名 `source` 时产生以下一系列逻辑失效：
- **`converter` 失效**：`converter` 接收到的是默认传入的同名属性旧值或 null，并非由 `mapping.source()` 指定的真实属性值，且转换后的结果随后被原始 `source` 属性值覆盖。
- **`trim / format` 失效**：同理，字符串的裁切与格式化只能作用于错误的初值，最终提取到的 `source` 真实属性值没有得到任何处理。
- **`SKIP_NULL` 或 null 策略误判**：由于 `skipNull` 和 `nullValueStrategy` 在前置阶段完成，使得如果初始同名值存在而显式 `source` 值为 null，或者初始同名值为 null 而 `source` 值为非 null 时，控制策略完全发生方向性误判。

### 修复方案
将 `mapping.source()` 的解析动作前置，使整条转换流水线（Pipeline）始终处理**真实的值源**：
1. **忽略策略最高优先级**：如果标记了 `ignore`，直接返回 target 原值。
2. **提取真实值源**：若指定了 `mapping.source()`，首先从 `source` 对象提取对应属性值并更新 `sourceValue`。
3. **空值判定与过滤**：根据更新后的真实 `sourceValue`，与当前 flags 的 `skipNull` 或映射注解的 `nullValueStrategy()` 进行过滤，匹配则跳过后续链式处理。
4. **格式化与修正**：执行 String 裁剪（`trim`）、定制化格式化（`format`）。
5. **转换器定制加工**：当指定了非默认的 `converter`，使用其实例转换。
6. **安全返回**：作为最终结果流出，后续通过类型适配（`Reflects.changeType`）写入目标属性。

---

## 2. CGLIB 与 Byte Buddy 性能与兼容性选型分析

### 技术特征横向对比

| 评估维度 | Spring CGLIB `BeanCopier` | Byte Buddy (动态代码生成) | 选型结论与建议 |
| :--- | :--- | :--- | :--- |
| **首次生成成本 (Creation overhead)** | 极低（Spring 高度优化了生成机制并内部重打包，API 最简化） | 中（Byte Buddy 的构造 API 和 DSL 略重，需要完成类型构建） | 两者对长期服务热路径而言差异不明显，但 CGLIB 在极速冷启动上略微占优。 |
| **热路径 copy 吞吐 (Warm copy throughput)** | 极高（生成的字节码只包含最轻量的直接 getter/setter 方法调用） | 极高（同上，生成等价的 setter 直接调用类） | 如果 Byte Buddy 没有采用复杂的 Delegation 分派机制，两者热路径性能不相上下。 |
| **JDK 8 兼容性** | 完美兼容。 | 完美兼容。 | 双方均稳定运行在 Java 8。 |
| **JDK 17/21 兼容性** | **潜在风险**。Spring 5.3.x repackaged 的 CGLIB 虽然处理了大量反射封装，但在 Java 17+ 严禁非法模块与包路径访问的背景下，仍有底层突破安全性限制的兼容隐忧。 | **优秀**。现代框架的标准首选，能与高版本 JDK 和 GraalVM 有效配合，不依赖非标准 API。 | 建议在现网升级至 JDK 17 前，优先保留 CGLIB，而在整体技术栈向新 JDK 架构重构时，全面重构 `BeanMapper` 的生成逻辑至 Byte Buddy。 |
| **代码实现复杂度** | 极低（调用 `BeanCopier.create`，热路径复用 `copy` 方法即可，高度开箱即用） | 高（需要自行使用 Byte Buddy 描述动态类定义、属性扫描、生成 getter/setter 桥接指令并管理 ClassLoader 缓存） | 在未有明确性能数据劣势下，不建议贸然重写 CGLIB Copier，否则易引入 ClassLoader 泄露和缓存碰撞。 |

---

## 3. 额外审查的系统性风险

- **哈希碰撞风险**：`BeanMapper#getConfig(from, to)` 当前使用 `Objects.hash(from, to)` 做唯一 Key。在高频及大量动态类生成的场景下，理论上存在碰撞几率。一旦碰撞，会错误复用别的对象的 `BeanCopier`。未来可评估升级为专有的 `Tuple<Class, Class>` 或 `ImmutablePair`。
- **ClassLoader 泄露风险**：无论 CGLIB 还是 Byte Buddy，如果动态类过多且 `getConfig` 的缓存不支持容量上限、弱引用（WeakReference）或按 ClassLoader 级隔离，在频繁热部署或大量临时/代理类转换场景下可能产生 Metaspace 溢出。

---

# 实施与回归记录 (Execution Record)

为了解决以上赋值 Bug、确保回归完全通过，并增强基础框架的类型转换兼容性，已完成以下工作：

## 1. 代码修改范围
- **`BeanMapper.java`**：
  - 在 `copy` 内部：通过 `skipNull` 和新增的 `logOnFail / throwOnFail` 标识，保证在遭遇不兼容属性复制时，如果仅配置了 `LOG_ON_MISS_MAPPING`，则跳过该属性而非直接崩溃，并保留原有目标值；对于显式注解映射或配置 `THROW_ON_MISS_MAPPING` 时保持严格报错行为。
  - 修正 `processMapping` 中的步骤顺序：**“提取指定 Source -> 判断 null 过滤 -> 裁剪 format -> 转换器 converter”**。
  - 使用 `Strings.splitByWholeSeparator(..., ", ")` 重构 `convertFromObjectString` 方法，避免日期时间（如 `2022-07-30 15:35:34`）中含有的空格字符被当做逗号后面的字符进行了断开拆分，保障了序列化/反序列化的一致性。
- **`Reflects.java`**：
  - 调整 `changeType` 的逻辑，**将自定义/显式注册的转换器（ConvertBean）执行时机移到了最前列**。
  - **核心补丁**：确保了对类似 `Decimal` / `DateTime` 到标准 `BigDecimal` / `Date` 的高频类型适配转换能够首选最优的注册处理器，杜绝了由于缺少 direct changeType method 而引起的 `NoSuchMethodException` 静默抛错或降级回退问题。

## 2. 自动化验证测试
- **新增测试用例** `BeanMapperAssignmentTest.java` 实现了极佳的精准覆盖：
  1. `explicitSourceValueIsTrimmedAndFormatted`：验证非同名 source 的 `trim` 和 `format` 格式化完全生效。
  2. `skipNullUsesExplicitSourceBeforeNullDecision`：验证 `SKIP_NULL` 能在提取真实值源后才做出决策。
  3. `ignoreNullStrategyUsesExplicitSourceBeforeNullDecision`：验证在多字段复杂同名干扰下，非同名 source 与 `Ignore` 策略正确结合，不再因旧属性状态产生误判。
  4. `converterReceivesExplicitSourceValue`：验证指定 `converter` 能完美接收真实的 `source` 属性值并流出正确转换后的目标值。
  5. `incompatibleImplicitPropertyIsSkippedWhenOnlyLoggingMissedMappings`：验证仅配置 `LOG_ON_MISS_MAPPING` 时对于类型不兼容属性的平滑降级（不崩溃）。
  6. `convertFromObjectStringSplitsByCommaSpaceToken`：验证包含标准日期时间、嵌套 map 等复杂属性的对象文本能够完整解析无截断。

### 回归命令及通过性结果
在本地多环境下多次执行自动化测试回归：
```powershell
mvn test -Dtest="ReflectsCompatibilityTest,BeanMapperAssignmentTest,TestUtil"
```
**运行结论**：
- `ReflectsCompatibilityTest`、`BeanMapperAssignmentTest`、以及基础的 `TestUtil#defineMapBean` & `TestUtil#normalMapBean` 全部通过。
- **累计运行 13 个关键核心转换与映射用例，100% SUCCESS，无任何异常、缺陷与行为破坏！**
