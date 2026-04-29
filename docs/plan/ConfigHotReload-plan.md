# 配置热加载基础能力计划

## 本次模式

**高性能模式（Netty 底层网络编程）**

## 1. 目标

为 `rxlib` 增加一套轻量配置热加载基础能力，支持普通 DTO 配置类，并能把配置变更映射到关联实例或运行时数据的重建、原地更新或跳过更新。

第一阶段只实现本地 YAML 文件变更监听；监听抽象需要保留数据库值变更、远程配置中心、RPC 推送等扩展位置。

## 2. 复用结论

- 复用 `YamlConfiguration` 作为本地 YAML 配置加载与文件监听底座。
- 复用 `YamlConfiguration.setWatchValidator(...)` 在文件快照被接受前做 DTO 转换和业务校验，失败时沿用当前回滚行为，避免无效配置污染运行态。
- 复用 `YamlConfiguration.onChanged` 接收本地文件变更事件。
- 复用 `Delegate` 作为配置变更、重载成功、重载失败事件容器。
- 不直接改造 `YamlConfiguration` 为泛型配置中心；新增一层 typed wrapper，避免破坏现有 YAML API 和全局 `RX_CONF` 行为。

## 3. 已落地包结构

```text
rxlib/src/main/java/org/rx/core/config
  ConfigSource.java
  ConfigChangedEventArgs.java
  ConfigValidator.java
  ConfigChangeDetector.java
  YamlConfigSource.java
  ConfigResource.java
  ConfigResourceBinding.java
  ConfigReloadEventArgs.java
```

新能力已收敛到 `org.rx.core.config`，未把配置热加载 API 散落到 `org.rx.core` 根包。

同时对 `YamlConfiguration` 增加了 `disableWatch()` 与 `close()`，用于释放内部 `FileWatcher`，避免配置源关闭后文件监听线程泄漏。

## 4. 核心抽象

### 4.1 配置源

`ConfigSource<TConfig>` 表示一个可启动、可关闭、可读取当前快照的配置来源。

当前形态：

```java
public abstract class ConfigSource<TConfig> extends Disposable
        implements EventPublisher<ConfigSource<TConfig>> {
    public final Delegate<ConfigSource<TConfig>, ConfigChangedEventArgs<TConfig>> onChanged = Delegate.create();

    public String getSourceId();

    public abstract TConfig current();

    public abstract long version();

    public abstract ConfigSource<TConfig> start();

    public TConfig reload();
}
```

行为约束：

- `current()` 返回最近一次已校验通过的配置快照。
- `version()` 单调递增，初始化加载成功为 `1`，每次接受新配置后递增。
- `onChanged` 只在新配置被接受后发布。
- 配置 DTO 视为发布后只读；框架不深拷贝 DTO，调用方不得修改 `current()` 返回对象。

### 4.2 配置变更事件

`ConfigChangedEventArgs<TConfig>` 携带新旧配置、版本号、来源标识与耗时。

建议字段：

```java
public final class ConfigChangedEventArgs<TConfig> extends EventArgs {
    private final String sourceId;
    private final String sourcePath;
    private final long version;
    private final TConfig oldConfig;
    private final TConfig newConfig;
    private final long loadMillis;
}
```

配置变更频率低，事件对象分配不是网络热点路径问题，可以优先保证语义完整。

### 4.3 校验与变更判断

```java
public interface ConfigValidator<TConfig> {
    boolean validate(TConfig config) throws Throwable;
}

public interface ConfigChangeDetector<TConfig> {
    boolean changed(TConfig oldConfig, TConfig newConfig) throws Throwable;
}
```

默认策略：

- `ConfigValidator` 为空时视为通过。
- `ConfigChangeDetector` 为空时每次接受文件事件都发布变更。
- `ConfigChangeDetector` 返回 `false` 时跳过本次 typed 配置发布，适合压制重复文件事件；它不是字段级自动 diff。
- 如果 DTO 明确实现稳定 `equals`，调用方可传入基于 `equals` 的 detector；不建议框架默认依赖 DTO `equals`，因为普通 DTO 未必覆盖该方法。

## 5. 本地 YAML 源

`YamlConfigSource<TConfig>` 包装 `YamlConfiguration`：

```java
public final class YamlConfigSource<TConfig> extends ConfigSource<TConfig> {
    public YamlConfigSource(String sourceId,
                            String key,
                            Type configType,
                            String... fileNames);
}
```

已落地能力：

- `key` 为空时读取整个 YAML；非空时调用 `YamlConfiguration.readAs(key, configType, true)`。
- `start()` 内部调用 `YamlConfiguration.enableWatch(...)`。
- `YamlConfiguration.setWatchValidator(...)` 里完成 DTO 转换、`ConfigValidator` 校验和临时缓存。
- `YamlConfiguration.onChanged` 触发后读取 typed DTO，更新 `AtomicReference<TConfig>` 与版本号，再发布 `ConfigChangedEventArgs`。
- 支持 `reload()` 或 `raiseChange()` 形式的手动触发，便于测试和管理端调用。
- 暴露 `setWatchRetry(...)`、`setDebounceMillis(...)`、`setValidator(...)`、`setChangeDetector(...)`。

注意事项：

- 文件保存通常会产生多次 create/modify 事件，第一版应支持 50-200ms 级 debounce 或合并调度。
- YAML 解析失败、DTO 转换失败、校验失败时保持旧配置与旧实例不变。
- `YamlConfiguration` 当前已经有 retry 和回滚逻辑，应优先复用，不重复实现文件读取细节。

## 6. 配置关联对象抽象

配置框架不直接知道关联对象是 `TcpServer`、`DnsServer` 这类大资源，还是路由表、限流表、内存索引这类小对象，应统一使用资源定义抽象。

建议定义：

```java
public interface ConfigResource<TConfig, TResource> {
    TResource create(TConfig config) throws Throwable;

    default boolean restartRequired(TConfig oldConfig, TConfig newConfig, TResource current) throws Throwable {
        return true;
    }

    default void apply(TConfig oldConfig, TConfig newConfig, TResource current) throws Throwable {
    }

    default void close(TResource resource) throws Throwable {
        if (resource instanceof AutoCloseable) {
            ((AutoCloseable) resource).close();
        }
    }
}
```

语义：

- 大对象默认 `restartRequired(...) == true`：先创建新实例，创建成功后原子切换，再关闭旧实例。
- 小对象可以继续走重建并原子替换；如果对象是可变运行时数据，也可以令 `restartRequired(...) == false`，对当前实例执行 `apply(...)`。
- `create(...)` 失败时必须保留旧实例继续服务。
- `close(...)` 失败只记录错误，不影响新实例可用性。

### 6.1 大对象与小对象策略

大对象，例如 `TcpServer`、`HybridServer`、`DnsServer`：

- 默认走重建切换，避免在运行中的 Channel/线程池/编解码链上做不完整变更。
- 新实例创建失败时旧实例继续对外服务。
- 端口独占场景无法并行启动时，由具体 `ConfigResource` 自行实现“先优雅关闭再启动”的策略，并接受短暂不可用风险。

小对象，例如路由表、ACL、限流阈值、上游选择器快照：

- 如果对象不可变，建议直接 `create(newConfig)` 后原子替换，读路径只读 `AtomicReference`。
- 如果对象可变且更新成本低，建议 `restartRequired=false` 并在 `apply(...)` 内完成原地更新。
- `apply(...)` 必须自己保证并发可见性，例如使用 `volatile`、`AtomicReference`、copy-on-write 容器或 EventLoop 线程亲和发布。

### 6.2 资源绑定器

`ConfigResourceBinding<TConfig, TResource>` 负责把配置源与资源对象绑定起来。

建议职责：

- 初始化时用 `source.current()` 创建首个实例。
- 监听 `source.onChanged`。
- 串行执行 reload，避免文件多次变更导致并发重启。
- 暴露 `current()` 获取当前实例。
- 暴露 `currentConfig()` 获取当前资源已成功应用的配置。
- 发布 `onReloaded`、`onReloadFailed` 事件。
- `close()` 时先解绑监听，再关闭当前实例和配置源。

核心切换流程：

```text
配置变更事件
  -> reload 串行队列
  -> 判断 restartRequired
  -> create 新实例或 apply 当前实例
  -> 成功后发布 onReloaded
  -> 失败则保留旧实例并发布 onReloadFailed
```

重启场景必须采用“先建新，后切换，再关旧”的顺序，避免错误配置或启动失败造成服务空窗。

## 7. 线程模型

- 文件监听线程只负责触发 reload 调度，不执行耗时实例重启。
- `ConfigResourceBinding` 使用单线程串行调度，避免同一个资源被并发 reload。
- 不允许在 Netty `EventLoop` 上阻塞等待配置重载完成。
- 网络实例重启时，具体资源实现必须遵守自身线程模型，例如在服务管理线程触发 bind/close，不在 I/O 回调里做阻塞等待。
- 配置 DTO 通过 `AtomicReference` 或 `volatile` 发布，读路径无锁。

## 8. 高性能与工程风险

内存泄漏：

- `YamlConfigSource.close()` 必须关闭内部 `FileWatcher`。
- `ConfigResourceBinding.close()` 必须关闭当前资源。
- 重启失败时新建的半成品资源需要尽力 `close()`。
- 对 Netty 资源，`close()` 必须释放 Channel、EventLoopGroup 或业务线程池引用。

背压：

- 文件事件需要 debounce 和串行 reload，避免编辑器连续保存触发重启风暴。
- reload 正在执行时，新事件只保留最新配置版本，避免队列无限增长。

连接生命周期：

- 对服务端实例建议先启动新实例成功再切换引用。
- 如果端口独占导致无法并行启动，需要资源实现提供定制策略，例如先优雅关闭旧实例再启动新实例，并明确短暂不可用风险。
- 对客户端实例重启要处理旧连接关闭、重连、半关闭和异常断开事件。

协议兼容：

- 配置变更可能影响编解码、加密、路由和上游选择，应由具体 `ConfigResource` 校验配置兼容性。
- 不兼容配置必须在 validator 阶段拒绝，不能等实例运行后失败。

对象分配：

- 配置热加载不是包处理热点路径，可以接受少量事件对象与 DTO 分配。
- 网络读写路径读取配置时应读取已发布引用，不应每次解析 YAML 或构造临时字符串。

## 9. 监控指标建议

配置层：

- `config.reload.count`
- `config.reload.fail.count`
- `config.reload.last_success_time`
- `config.reload.last_failure_time`
- `config.reload.duration`
- `config.version`
- `config.source.file.last_modified`
- `config.reload.skipped.count`

资源层：

- `resource.restart.count`
- `resource.restart.fail.count`
- `resource.current.version`
- `resource.reload.queue.pending`
- `resource.reload.in_progress`

网络项目必须同时关注：

- Netty 堆外内存占用：`PooledByteBufAllocatorMetric.usedDirectMemory`
- 活跃连接数
- 入站/出站吞吐
- P50/P95/P99 延迟
- 写队列积压与高低水位触发次数
- EventLoop pending task 数与执行耗时

## 10. 当前执行状态

已完成：

- 新增 `org.rx.core.config.ConfigSource`
- 新增 `ConfigChangedEventArgs`
- 新增 `ConfigReloadEventArgs`
- 新增 `ConfigValidator`
- 新增 `ConfigChangeDetector`
- 新增 `YamlConfigSource`
- 新增 `ConfigResource`
- 新增 `ConfigResourceBinding`
- `YamlConfiguration` 增加 `disableWatch()` 和 `close()`
- 新增 `ConfigHotReloadTest`

已覆盖测试：

- YAML 初始加载为 DTO。
- 文件变更后发布 typed 配置事件。
- validator 拒绝无效配置时保留旧配置。
- `ConfigResourceBinding` 重启成功后切换实例并关闭旧实例。
- 新实例创建失败时保留旧实例。
- `restartRequired == false` 时执行 `apply(...)` 而不重建实例。

已执行：

```text
mvn -pl rxlib "-Dtest=org.rx.core.config.ConfigHotReloadTest" test
mvn -pl rxlib -DskipTests compile
```

结果：全部通过，5 个目标测试成功，模块编译成功。

## 11. 后续接入步骤

1. 新增 `org.rx.core.config` 抽象类与事件类。
2. 新增 `YamlConfigSource<TConfig>`，复用 `YamlConfiguration` 完成本地文件监听和 DTO 转换。
3. 新增 `ConfigResource<TConfig, TResource>` 与 `ConfigResourceBinding<TConfig, TResource>`。
4. 补充单元测试：
   - YAML 初始加载为 DTO。
   - 文件变更后发布 typed 配置事件。
   - validator 拒绝无效配置时保留旧配置。
   - `ConfigResourceBinding` 重启成功后切换实例并关闭旧实例。
   - 新实例创建失败时保留旧实例。
   - `restartRequired == false` 时执行 `apply(...)` 而不重建实例。
5. 执行验证：
   - `mvn -pl rxlib -DskipTests compile`
   - `mvn -pl rxlib "-Dtest=org.rx.core.config.*Test" test`
   - 如后续接入网络实例，再补对应集成测试，例如 `RemotingTest` 或具体 server/client 集成测试。

以上 1-4 已完成。后续真正接入 `TcpServer`、`DnsServer` 或 socks 相关实例时，需要为对应资源补集成测试。

## 12. 非目标

- 第一阶段不实现数据库监听或远程配置中心，只保留抽象扩展点。
- 第一阶段不把 `YamlConfiguration.RX_CONF` 改成全局热加载容器。
- 第一阶段不自动 diff DTO 字段，也不做反射式局部更新。
- 第一阶段不为网络服务统一规定端口重绑定策略，该策略交给具体 `ConfigResource` 实现。

## 13. 推荐使用形态

示例意图：

```java
YamlConfigSource<MyServerConfig> source =
        new YamlConfigSource<MyServerConfig>("myServer", "server", MyServerConfig.class, "conf.yml")
                .setValidator(conf -> conf.getPort() > 0);

ConfigResourceBinding<MyServerConfig, MyServer> binding =
        new ConfigResourceBinding<MyServerConfig, MyServer>(source, new MyServerResource())
                .start();

MyServer server = binding.current();
```

`MyServerResource` 负责判断哪些字段可原地更新，哪些字段必须重启实例。这样配置框架保持通用，网络生命周期仍由具体资源掌控。
