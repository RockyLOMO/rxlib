# 配置热加载

实现在 `org.rx.core.config`。

- `ConfigSource` 提供 `current()`、`version()`、`start()`、`reload()`，变更走 `onChanged`。
- `YamlConfigSource` 读本地 YAML。`ConfigResource` / `ConfigResourceBinding` 把配置绑到资源对象。
- RSS Client 用 `YamlConfigSource` 监听 `conf.yml`。校验通过后 `RssRuntime.reload` 做切换；失败则保持上一份配置。

没有数据库监听，也没有远程配置中心。
