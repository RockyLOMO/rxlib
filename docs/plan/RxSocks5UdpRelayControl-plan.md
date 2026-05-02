# RX SOCKS5 UDP Relay Control 长期方案

本文档是 `UdpSocks5UpstreamPortHopping-plan.md` 的长期扩展方案。

当前端口跳跃已经实现标准 SOCKS5 兼容模式：一个逻辑 `SocksUdpUpstream` 可以持有多个远端 SOCKS5 `UDP_ASSOCIATE` relay 端口，并通过自适应扩容、lease pool、单 hop 摘除补洞来控制资源开销。

但标准 SOCKS5 的天然限制是：

```text
1 个 UDP_ASSOCIATE relay ≈ 1 条 TCP control channel + 1 个 UDP relay port
N 个 hop ≈ N 条 TCP control channel + N 个 UDP relay port
```

如果 Proxy A 和 Proxy B 两端都是 rxlib，则可以扩展私有控制协议，让一个 TCP/RPC control session 管理整个 UDP relay group，从而把控制面开销从 `N 条 TCP` 降低到 `1 条 TCP/RPC`。

## 1. 结论

可以实现，而且建议长期优先走 `RssRpcApp / SocksRpcContract` 扩展，而不是修改标准 SOCKS5 `UDP_ASSOCIATE` 状态机。

本轮执行结果：

- 已完成 RSS RPC relay group 第一阶段实现，暂不实现 `RX_SOCKS5_BATCH`。
- `SocksRpcContract` 已增加默认 capability 和 UDP relay group 方法，旧实现默认 `unsupported`，不破坏二进制兼容语义。
- `SocksUdpUpstream` 已接入 `AUTO/RSS_RPC/SOCKS5_COMPAT` 模式选择；默认 `AUTO + udpRelayControlFallbackToSocks5=true`。
- 兼容结论已验证：没有 `SocksRpcContract` facade、对端不是 rxlib、旧版本 capability 不支持或 RPC 调用失败时，都会回退标准 SOCKS5 `UDP_ASSOCIATE` 模式。
- 执行结果已合并到 `UdpSocks5UpstreamPortHopping-plan.md`。

推荐最终形态：

```text
两端都是 rxlib：
  1 条 RssRpcApp / SocksRpcContract 控制会话
  + N 个远端 UDP relay port
  + 1 个逻辑 relay group

任意一端不是 rxlib：
  回退标准 SOCKS5 兼容模式
  1 个 UDP relay = 1 条 TCP control channel
```

原因：

- 标准 SOCKS5 的 `UDP_ASSOCIATE` 语义通常绑定一条 TCP control connection 的生命周期；强行在一个标准 TCP control 上复用多个 UDP relay 不利于兼容第三方实现。
- rxlib 两端可控时，控制面可以走 `SocksRpcContract` / `RssRpcApp` 私有 RPC，不影响标准 SOCKS5 wire protocol。
- UDP 数据面仍可保持当前 SOCKS5 UDP request/response header 格式，降低 `SocksUdpRelayHandler`、`SSUdpProxyHandler`、`Udp2rawHandler` 的改造成本。

## 2. 目标

- 在 A/B 两端都是 rxlib 时，支持一个控制会话管理多个 UDP relay port。
- 保持现有标准 SOCKS5 兼容模式，不破坏第三方 SOCKS5 server/client。
- 控制面支持批量申请、追加、释放、重置、心跳、能力协商。
- 数据面继续使用 UDP relay port 进行端口分散，仍能降低单端口/五元组限速风险。
- 与已有 `UdpPortHoppingConfig`、自适应扩容、lease pool、单 hop 摘除补洞兼容。
- 为后续跨 relay RDNT 去重窗口和 `spreadRedundantCopies` 打基础。

## 3. 非目标

- 不修改第三方标准 SOCKS5 服务器的兼容路径。
- 不要求一个 UDP socket 虚拟出多个端口；如果要规避按端口/五元组限速，远端仍必须真实绑定多个 UDP relay port。
- 第一阶段不启用跨 relay 冗余副本分散；`spreadRedundantCopies` 仍保持 false。
- 第一阶段不把 UDP payload 改成全新私有格式，优先复用现有 SOCKS5 UDP header。

## 4. 总体架构

### 4.1 兼容模式：当前实现

```text
Proxy A SocksUdpUpstream
  -> TCP control #1 -> SOCKS5 UDP_ASSOCIATE -> UDP relay port 1
  -> TCP control #2 -> SOCKS5 UDP_ASSOCIATE -> UDP relay port 2
  -> TCP control #3 -> SOCKS5 UDP_ASSOCIATE -> UDP relay port 3
```

优点：

- 完全兼容标准 SOCKS5；
- 任意第三方 SOCKS5 upstream 可用。

缺点：

- 多 hop 会线性增加 TCP control channel；
- 控制面握手和 channel 数量较重。

### 4.2 rxlib 私有 RPC 控制模式：推荐长期方向

```text
Proxy A SocksUdpUpstream
  -> 1 条 RssRpcApp / SocksRpcContract control session
      -> openUdpRelayGroup(count=3)
      <- groupId + relayPort1 + relayPort2 + relayPort3 + token

UDP data:
  A UDP outbound -> B relayPort1 / relayPort2 / relayPort3
```

资源模型：

```text
3 hop 标准模式：3 条 TCP control + 3 个 UDP relay port
3 hop RX RPC 模式：1 条 RPC/control + 3 个 UDP relay port
```

### 4.3 私有 SOCKS5 batch command：备选方向

```text
TCP control #1
  -> RX_UDP_ASSOCIATE_BATCH(count=3)
  <- relayPort1, relayPort2, relayPort3
```

不推荐优先实现。原因是需要扩展 SOCKS5 编解码状态机，并且与第三方 SOCKS5 实现没有兼容价值。RPC/facade 已经存在，直接扩展 `SocksRpcContract` 更自然。

## 5. 模式选择和自动回退

新增控制模式枚举：

```java
public enum UdpRelayControlMode {
    AUTO,
    SOCKS5_COMPAT,
    RSS_RPC,
    RX_SOCKS5_BATCH
}
```

推荐默认：

```java
private UdpRelayControlMode udpRelayControlMode = UdpRelayControlMode.AUTO;
```

选择规则：

```text
AUTO:
  1. 如果 next.getFacade() != null 且 capability 支持 UDP_RELAY_GROUP，则使用 RSS_RPC
  2. 否则如果未来实现 RX_SOCKS5_BATCH 且 peer 支持，则使用 RX_SOCKS5_BATCH
  3. 否则回退 SOCKS5_COMPAT

SOCKS5_COMPAT:
  强制使用当前标准 SOCKS5 UDP_ASSOCIATE 模式

RSS_RPC:
  要求 rxlib peer 支持 RssRpcApp 扩展，否则 route 初始化失败或按配置回退

RX_SOCKS5_BATCH:
  预留给未来私有 SOCKS5 command
```

建议再增加：

```java
private boolean udpRelayControlFallbackToSocks5 = true;
```

语义：

- `true`：RPC 不可用、能力不支持、调用失败时回退标准 SOCKS5；
- `false`：要求必须走 rxlib 私有控制模式，失败则 route init 失败。

## 6. SocksRpcContract 扩展设计

当前 `SocksRpcContract` 已有：

```java
boolean claimUdpRelay(int relayPort, InetSocketAddress clientAddr, String token);
boolean resetUdpRelay(int relayPort, String token);
```

当前实现已经把 RPC 控制面方法统一改为带 `token` 入参；旧 rxlib 对端如果仍是旧签名，会在能力探测或调用阶段失败，并按 `AUTO + fallback` 回退标准 SOCKS5：

```java
public interface SocksRpcContract extends AutoCloseable, DnsServer.ResolveInterceptor {
    static String rpcToken() {
        return RxConfig.INSTANCE.getRtoken();
    }

    static boolean isValidRpcToken(String token) {
        return token != null && token.equals(RxConfig.INSTANCE.getRtoken());
    }

    static void requireValidRpcToken(String token) {
        if (!isValidRpcToken(token)) {
            throw new SecurityException("invalid rpc token");
        }
    }

    default SocksRpcCapabilities capabilities(String token) {
        return SocksRpcCapabilities.EMPTY;
    }

    default UdpRelayGroupOpenResult openUdpRelayGroup(UdpRelayGroupOpenRequest request, String token) {
        return UdpRelayGroupOpenResult.unsupported();
    }

    default UdpRelayGroupUpdateResult addUdpRelays(String groupId, int count, String token) {
        return UdpRelayGroupUpdateResult.unsupported();
    }

    default boolean removeUdpRelay(String groupId, int relayPort, String token) {
        return false;
    }

    default boolean heartbeatUdpRelayGroup(String groupId, String token) {
        return false;
    }

    default boolean closeUdpRelayGroup(String groupId, String token) {
        return false;
    }
}
```

当前代码实现采用 `String.equals()` 做 `rtoken` 字符串值比较，避免 token 校验产生 `byte[]` 临时分配。

### 6.1 Capability

```java
public final class SocksRpcCapabilities implements Serializable {
    public static final int UDP_RELAY_GROUP = 1;
    public static final int UDP_RELAY_GROUP_ADD = 1 << 1;
    public static final int UDP_RELAY_GROUP_HEARTBEAT = 1 << 2;
    public static final int UDP_RELAY_GROUP_SHARED_DEDUP = 1 << 3;
    public static final int UDP_RELAY_GROUP_BATCH_RESET = 1 << 4;

    private int flags;
    private int version;
    private int maxRelaysPerGroup;
    private int maxGroups;
}
```

### 6.2 Open request

```java
public final class UdpRelayGroupOpenRequest implements Serializable {
    private String clientId;
    private InetSocketAddress clientAddr;
    private UnresolvedEndpoint firstDestination;
    private int initialRelayCount;
    private int minActiveRelays;
    private int maxRelayCount;
    private long idleTimeoutMillis;
    private boolean sharedDedupRequired;
    private Map<String, String> attributes;
}
```

### 6.3 Open result

```java
public final class UdpRelayGroupOpenResult implements Serializable {
    private boolean success;
    private boolean supported;
    private String errorCode;
    private String errorMessage;
    private String groupId;
    private String token;
    private long expireAtMillis;
    private List<UdpRelayEndpoint> relays;
}
```

### 6.4 Relay endpoint

```java
public final class UdpRelayEndpoint implements Serializable {
    private String relayId;
    private InetSocketAddress relayAddress;
    private int weight;
    private long expireAtMillis;
}
```

## 7. RssRpcApp 实现方向

`RssRpcApp` 当前实现了 `SocksRpcContract`，并通过 `SocksProxyServer` 完成 relay claim/reset。

长期扩展建议：

```java
public final class RssRpcApp implements SocksRpcContract {
    @Override
    public SocksRpcCapabilities capabilities() {
        return svrSide.get().socksRpcCapabilities();
    }

    @Override
    public UdpRelayGroupOpenResult openUdpRelayGroup(UdpRelayGroupOpenRequest request) {
        return svrSide.get().openUdpRelayGroup(request);
    }

    @Override
    public UdpRelayGroupUpdateResult addUdpRelays(String groupId, int count) {
        return svrSide.get().addUdpRelays(groupId, count);
    }

    @Override
    public boolean removeUdpRelay(String groupId, int relayPort) {
        return svrSide.get().removeUdpRelay(groupId, relayPort);
    }

    @Override
    public boolean heartbeatUdpRelayGroup(String groupId) {
        return svrSide.get().heartbeatUdpRelayGroup(groupId);
    }

    @Override
    public boolean closeUdpRelayGroup(String groupId) {
        return svrSide.get().closeUdpRelayGroup(groupId);
    }
}
```

`RssRpcApp` 只做 RPC facade，不直接管理 UDP channel 细节。真正的 relay group 生命周期放到 `SocksProxyServer` 或独立 manager。

## 8. Proxy B 侧 RelayGroupManager

新增服务端管理器，建议命名：

```java
final class UdpRelayGroupManager extends Disposable {
    UdpRelayGroup open(UdpRelayGroupOpenRequest request);
    List<UdpRelayEndpoint> addRelays(String groupId, int count);
    boolean removeRelay(String groupId, int relayPort);
    boolean heartbeat(String groupId);
    boolean close(String groupId);
}
```

### 8.1 UdpRelayGroup

```java
final class UdpRelayGroup {
    final String groupId;
    final String token;
    final InetSocketAddress clientAddr;
    final UnresolvedEndpoint firstDestination;
    final Map<Integer, UdpRelayEntry> relays;
    final long createdAtMillis;
    volatile long lastActiveAtMillis;
    volatile boolean closed;
}
```

### 8.2 UdpRelayEntry

```java
final class UdpRelayEntry {
    final String relayId;
    final int relayPort;
    final Channel udpChannel;
    volatile long lastActiveAtMillis;
    volatile int weight;
}
```

### 8.3 生命周期

```text
openUdpRelayGroup
  -> 创建 groupId/token
  -> 绑定 N 个 UDP relay channel/port
  -> 绑定 clientAddr 或安全 token
  -> 注册到 relayGroupMap 和 relayPortMap
  -> 返回 relay endpoint 列表

UDP 包到达 relayPort
  -> relayPortMap 找到 UdpRelayEntry
  -> 校验 clientAddr 或 token
  -> 解析 SOCKS5 UDP header
  -> 转发到真实 dest

closeUdpRelayGroup / idle timeout
  -> 关闭所有 relay channel
  -> 移除 relayPortMap
  -> 移除 groupMap
```

## 9. Proxy A 侧 SocksUdpUpstream 改造

`SocksUdpUpstream` 当前的 holder 基本以 `SessionHolder` 为单位。长期可以抽象出统一 holder：

```java
interface UdpRelayHolder extends AutoCloseable {
    InetSocketAddress relayAddress();
    boolean isValid();
    boolean pooled();
    String groupId();
}
```

现有模式：

```text
Socks5UdpSessionHolder implements UdpRelayHolder
Socks5UdpLeaseHolder implements UdpRelayHolder
```

新增 RPC group 模式：

```text
RpcUdpRelayGroupHolder
  -> 内部持有 groupId/token/control facade
  -> 包含多个 RpcUdpRelayHolder
```

建议第一阶段不要大改继承层次，只在 `SocksUdpUpstream.acquireGroup()` 中加入新分支：

```java
private SessionGroup acquireGroup(Channel channel) {
    if (shouldUseRpcRelayGroup()) {
        SessionGroup group = acquireRpcRelayGroup(channel);
        if (group != null && group.isValid()) {
            return group;
        }
        if (!config.isUdpRelayControlFallbackToSocks5()) {
            throw new IllegalStateException("RX UDP relay group unavailable");
        }
    }
    return acquireSocks5CompatGroup(channel);
}
```

`acquireRpcRelayGroup()` 流程：

```text
1. facade.capabilities()
2. 检查 UDP_RELAY_GROUP 支持
3. openUdpRelayGroup(initialCount/minActive/max)
4. 为返回的每个 relay endpoint 创建轻量 holder
5. holder 不再拥有独立 TCP control channel
6. group close 时调用 closeUdpRelayGroup(groupId)
```

自适应扩容：

```text
标准模式：acquireHolder() -> UDP_ASSOCIATE 或 lease pool
RPC group：addUdpRelays(groupId, 1) -> 追加 relay endpoint
```

单 hop 摘除：

```text
标准模式：remove holder + close/reset 对应 lease/session
RPC group：removeUdpRelay(groupId, relayPort)
```

整组关闭：

```text
标准模式：close 每个 holder
RPC group：closeUdpRelayGroup(groupId)
```

## 10. 数据面格式

第一阶段建议继续复用 SOCKS5 UDP datagram 格式：

```text
+----+------+------+----------+----------+----------+
|RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
+----+------+------+----------+----------+----------+
```

好处：

- `SocksUdpRelayHandler` 现有解析逻辑可复用；
- Shadowsocks 场景 4 不需要大改；
- 第三方兼容路径和 rxlib 私有路径的数据面差异较小。

如需增强安全校验，可以在服务端 group 中继续绑定 `clientAddr`。如果存在 NAT 端口变化或多出口漂移，再考虑私有 header：

```text
RXUDP magic + version + groupIdHash + tokenMac + socks5UdpPayload
```

第一阶段不建议引入私有 UDP header，以减少改造风险。

## 11. 安全与授权

### 11.1 基础模式：clientAddr 绑定

open group 时记录 `clientAddr`：

```text
clientAddr = Proxy A 对 Proxy B 发 UDP 的源 IP:port
```

Proxy B relay 收包时校验 sender：

```text
sender 必须等于 group.clientAddr
```

优点：简单、无额外 UDP payload 开销。

缺点：如果 NAT 映射变化，可能导致 relay 拒收。

### 11.2 RPC 授权：rtoken

所有 `SocksRpcContract` 控制面方法都必须携带 `token` 入参，服务端校验：

```text
token == RxConfig.INSTANCE.getRtoken()
```

覆盖范围：

- `fakeEndpoint(hash, endpoint, token)`
- `addWhiteList(endpoint, token)`
- `capabilities(token)`
- `resetUdpRelay(relayPort, token)`
- `claimUdpRelay(relayPort, clientAddr, token)`
- `openUdpRelayGroup(request, token)`
- `addUdpRelays(groupId, count, token)`
- `removeUdpRelay(groupId, relayPort, token)`
- `heartbeatUdpRelayGroup(groupId, token)`
- `closeUdpRelayGroup(groupId, token)`

`UdpRelayGroupManager` 内部仍可生成 group token 作为预留字段，但 RPC 授权不再依赖该随机 group token，而是统一依赖 `app.rtoken`；通过 RPC 暴露的 `openUdpRelayGroup(..., token)` 成功结果会回填已校验的 `rtoken`，供后续 add/remove/heartbeat/close 复用。服务端 token 校验失败时抛出 `SecurityException`，客户端 `AUTO` 模式捕获 RPC 失败后仍会走标准 SOCKS5 回退。

如果未来引入 RXUDP header，则每个 UDP datagram 可再叠加数据面 `tokenMac`：

```text
tokenMac = HMAC(groupToken, timestamp + relayId + seq + payloadHash)
```

第一阶段只做 RPC 控制面授权，不要求数据面携带私有 header。

## 12. 心跳与超时

RPC group 不再依赖 N 条 TCP control channel 的 closeFuture 来表达 relay 生命周期，因此必须显式管理超时。

建议：

```java
private long udpRelayGroupIdleMillis = 300_000L;
private long udpRelayGroupHeartbeatMillis = 30_000L;
```

Proxy A：

```text
route 活跃时不需要额外 heartbeat，以 UDP traffic 刷新活跃时间
route 长时间无 UDP traffic 但仍希望保留 group 时，调用 heartbeatUdpRelayGroup
route close 时调用 closeUdpRelayGroup
```

Proxy B：

```text
定期扫描 group.lastActiveAtMillis
超过 idle timeout -> close group
```

## 13. 与端口跳跃现有能力的关系

### 13.1 固定 hop

```text
RPC openUdpRelayGroup(count=hopCount)
```

### 13.2 自适应 hop

```text
初始：openUdpRelayGroup(count=minHopCount)
扩容：addUdpRelays(groupId, 1)
补洞：addUdpRelays(groupId, 1)
收缩/摘除：removeUdpRelay(groupId, relayPort)
```

### 13.3 lease pool

RPC group 模式下，Proxy A 侧不需要为每个 hop borrow SOCKS5 UDP lease。Proxy B 侧可以内部维护 UDP relay channel pool，但这是服务端本地优化，和当前 A 侧 `Socks5UpstreamPoolManager.UdpLeasePool` 分开。

建议：

```text
SOCKS5_COMPAT：继续使用当前 UDP lease pool
RSS_RPC：跳过 A 侧 UDP lease pool，直接 RPC 管理 relay group
```

### 13.4 单 hop 摘除补洞

当前逻辑可复用，只是 holder 的 close/replenish 操作不同：

```text
SOCKS5_COMPAT holder close -> close/reset session/lease
RSS_RPC holder close -> removeUdpRelay(groupId, relayPort)
RSS_RPC replenish -> addUdpRelays(groupId, 1)
```

## 14. 失败回退策略

### 14.1 初始化阶段

```text
capabilities 不支持 UDP_RELAY_GROUP
  -> fallbackToSocks5=true：走标准 SOCKS5
  -> fallbackToSocks5=false：route init fail

openUdpRelayGroup 失败
  -> fallbackToSocks5=true：走标准 SOCKS5
  -> fallbackToSocks5=false：route init fail
```

### 14.2 运行阶段

```text
addUdpRelays 失败
  -> 保持当前 active relays，不影响转发
  -> 记录 metric，按 cooldown 重试

removeUdpRelay 失败
  -> 本地先摘除，服务端依靠 idle timeout 回收

heartbeat 失败
  -> 标记 group 可疑；连续失败则 close local group 并回退重建

closeUdpRelayGroup 失败
  -> 本地清理，服务端依靠 idle timeout 回收
```

### 14.3 自动降级

如果 RPC group 连续失败超过阈值，打开短期 breaker：

```text
udpRelayControlBreakerOpenMillis = 60_000
```

breaker 打开期间 `AUTO` 直接走 `SOCKS5_COMPAT`，避免每条 route 都先打失败 RPC。

## 15. 指标与日志

新增 metrics：

```text
socks.udp.relay.control.mode.count{mode=rss_rpc|socks5_compat|fallback}
socks.udp.relay.group.open.count{result=success|fail|unsupported}
socks.udp.relay.group.active.count
socks.udp.relay.group.relay.count
socks.udp.relay.group.add.count{result=success|fail}
socks.udp.relay.group.remove.count{result=success|fail}
socks.udp.relay.group.close.count{result=success|fail|timeout}
socks.udp.relay.group.heartbeat.count{result=success|fail}
socks.udp.relay.control.breaker.count{action=open|close}
```

日志建议：

- capability 不支持时 debug；
- `RSS_RPC` 模式失败并 fallback 时 warn；
- `fallbackToSocks5=false` 且失败时 error；
- group idle timeout 回收时 debug；
- relay group 超过推荐上限时 warn。

## 16. 配置建议

新增配置字段建议放在 `SocksConfig` 或 `UdpPortHoppingConfig` 下。

```java
private UdpRelayControlMode udpRelayControlMode = UdpRelayControlMode.AUTO;
private boolean udpRelayControlFallbackToSocks5 = true;
private int udpRelayControlMaxRelaysPerGroup = 4;
private long udpRelayGroupIdleMillis = 300_000L;
private long udpRelayGroupHeartbeatMillis = 30_000L;
private int udpRelayControlFailureThreshold = 5;
private long udpRelayControlBreakerOpenMillis = 60_000L;
```

推荐线上默认：

```java
bConf.setUdpRelayControlMode(UdpRelayControlMode.AUTO);
bConf.setUdpRelayControlFallbackToSocks5(true);
bConf.setUdpRelayControlMaxRelaysPerGroup(3);
bConf.setUdpRelayGroupIdleMillis(300_000L);
bConf.setUdpRelayGroupHeartbeatMillis(30_000L);
```

## 17. 实施阶段

### 阶段 A：RPC 能力协商

- [x] 新增 `SocksRpcCapabilities`。
- [x] `SocksRpcContract` 增加默认 `capabilities()`。
- [x] `RssRpcApp` 实现 capabilities。
- [x] `SocksUdpUpstream` 在 `AUTO` 模式下探测 capability。
- [x] capability 不支持时自动回退当前 SOCKS5 兼容模式。

### 阶段 B：服务端 RelayGroupManager

- [x] 新增 `UdpRelayGroupManager`。
- [x] 支持 `open/add/remove/heartbeat/close`。
- [x] Proxy B 侧维护 `groupId -> group` 和 `relayPort -> entry`。
- [x] 支持 idle timeout 自动回收。
- [x] 数据面继续使用 SOCKS5 UDP header。

### 阶段 C：RssRpcApp 扩展

- [x] `SocksRpcContract` 增加 group 默认方法。
- [x] `RssRpcApp` 调用 `SocksProxyServer` 的 group 管理方法。
- [x] 保持旧实现默认 unsupported，不破坏第三方/旧版本。

### 阶段 D：SocksUdpUpstream 接入 RSS_RPC 模式

- [x] 新增 `UdpRelayControlMode` 配置。
- [x] `acquireGroup()` 优先尝试 `acquireRpcRelayGroup()`。
- [x] RPC group holder 支持 `select/snapshot/contains/remove/add`。
- [x] 自适应扩容改为 `addUdpRelays(groupId, 1)`。
- [x] group close 调用 `closeUdpRelayGroup(groupId)`。
- [x] 失败按 `fallbackToSocks5` 决定是否回退。

### 阶段 E：breaker 与观测

- [x] RPC 连续失败打开 breaker。
- [x] breaker 打开期间 `AUTO` 直接走 SOCKS5 兼容模式。
- [x] 增加 group open/add/remove/close/heartbeat 指标。
- [ ] 压测对比 TCP control channel 数量。

### 阶段 F：跨 relay RDNT 共享去重窗口

- [ ] 在 `UdpRelayGroup` 中加入共享 dedup window。
- [ ] 去重 key 使用 `groupId + destAddr + seqId`。
- [ ] capability 增加 `UDP_RELAY_GROUP_SHARED_DEDUP`。
- [ ] 支持 `spreadRedundantCopies=true`。
- [ ] 验证真实目标不会收到重复 payload。

## 18. 测试计划

### 18.1 单元测试

```text
SocksRpcCapabilitiesTest
UdpRelayGroupManagerTest
SocksUdpUpstreamRelayControlModeTest
RssRpcUdpRelayGroupTest
```

覆盖：

- capability 支持/不支持；
- `AUTO` 模式选择 RSS_RPC 或 SOCKS5_COMPAT；
- `fallbackToSocks5=true/false` 行为；
- group open 返回多个 relay；
- add/remove/heartbeat/close 生命周期；
- idle timeout 自动清理；
- RPC add 失败不影响已有 UDP 转发。

### 18.2 集成测试

rxlib 两端：

```text
ShadowsocksClient -> ShadowsocksServer -> Proxy A(rxlib)
  -> RSS_RPC openUdpRelayGroup -> Proxy B(rxlib)
  -> UDP dest
```

验证：

- `hopCount=3` 时 Proxy A 到 Proxy B 只有 1 条 RPC/control 会话；
- UDP 数据分散到 3 个真实 relay port；
- 断开一个 relay 后可 remove + add 补洞；
- close route 后 Proxy B group 清理完整。

非 rxlib 或 capability 不支持：

```text
Proxy A(rxlib) -> third-party SOCKS5
```

验证：

- 自动回退标准 SOCKS5 `UDP_ASSOCIATE`；
- 行为与当前兼容模式一致；
- 不调用 group RPC 方法。

### 18.3 压测

对比：

```text
1000 route, maxHopCount=3
SOCKS5_COMPAT：理论最多 3000 条 TCP control
RSS_RPC：理论 1 条长 RPC control 或每 upstream 少量 RPC control + 3000 个 UDP relay
```

关注：

- TCP control channel 数；
- UDP relay port 数；
- route init 延迟；
- 扩容延迟；
- Proxy B 堆外内存；
- ctxMap/groupMap 残留；
- 失败回退次数。

## 19. Mermaid 流程图

### 19.1 AUTO 模式选择

```mermaid
flowchart TD
    A["SocksUdpUpstream acquireGroup"] --> B{"udpRelayControlMode"}
    B -- "SOCKS5_COMPAT" --> C["标准 UDP_ASSOCIATE/lease pool"]
    B -- "RSS_RPC" --> D["检查 facade + capability"]
    B -- "AUTO" --> E["检查 next.getFacade"]
    E --> F{"capability 支持 UDP_RELAY_GROUP?"}
    F -- "是" --> G["openUdpRelayGroup"]
    F -- "否" --> C
    D --> H{"支持且调用成功?"}
    H -- "是" --> I["创建 RPC SessionGroup"]
    H -- "否且 fallback=true" --> C
    H -- "否且 fallback=false" --> J["route init fail"]
    G --> K{"open 成功?"}
    K -- "是" --> I
    K -- "否且 fallback=true" --> C
    K -- "否且 fallback=false" --> J
```

### 19.2 RPC relay group 生命周期

```mermaid
flowchart TD
    A["Proxy A route init"] --> B["RssRpcApp.openUdpRelayGroup"]
    B --> C["Proxy B UdpRelayGroupManager.open"]
    C --> D["绑定 N 个 UDP relay port"]
    D --> E["返回 groupId/token/relay list"]
    E --> F["Proxy A SessionGroup 使用 relay list round-robin"]
    F --> G{"需要扩容?"}
    G -- "是" --> H["addUdpRelays(groupId,1)"]
    H --> F
    F --> I{"某 relay 失效?"}
    I -- "是" --> J["removeUdpRelay + add 补洞"]
    J --> F
    F --> K{"route close/idle"}
    K -- "是" --> L["closeUdpRelayGroup"]
    L --> M["Proxy B 释放所有 relay port"]
```

## 20. 风险点

- RPC control session 本身需要稳定；如果它频繁断开，group 生命周期需要依靠 heartbeat/idle timeout 补偿。
- 如果只用 clientAddr 绑定，NAT 端口变化会导致 UDP relay 拒收；必要时引入 tokenMac 私有 UDP header。
- RSS_RPC 模式减少的是 TCP control channel，不减少 UDP relay port；端口跳跃要真实生效仍然需要多个 UDP port。
- batch group 模式下，服务端 groupMap/relayPortMap 清理必须严格，否则更容易出现 relay port 泄漏。
- 跨 relay RDNT 去重没有实现前，仍不能把同一冗余组副本拆到不同 relay。

## 21. 最终推荐

短期继续保持当前标准兼容实现作为 baseline。

长期按以下路线推进：

```text
1. SocksRpcContract 增加 capabilities 和 UDP relay group 默认方法
2. RssRpcApp 实现 UDP relay group 控制
3. Proxy B 增加 UdpRelayGroupManager
4. SocksUdpUpstream AUTO 模式优先走 RSS_RPC
5. RPC 不可用时自动回退 SOCKS5_COMPAT
6. 压测确认 TCP control channel 从 N 降为 1 或少量长连接
7. 再做跨 relay RDNT 共享去重窗口和 spreadRedundantCopies
```

推荐优先实现 `RSS_RPC`，暂缓 `RX_SOCKS5_BATCH`。这样可以最大程度复用现有 `RssRpcApp`、`SocksRpcContract`、`SocksProxyServer` 能力，并保持标准 SOCKS5 兼容路径稳定。

## 22. 跨 relay RDNT 共享去重窗口评估

本节汇总跨 relay RDNT 共享去重窗口评估，作为 RSS RPC relay group 后续阶段 F 的设计依据。

### 1. 结论

跨 relay RDNT 共享去重窗口不是 RSS RPC relay group 第一阶段的必需项。

它的主要价值只在准备开启：

```java
spreadRedundantCopies = true;
```

时才明显。也就是把同一个 RDNT 冗余组的多个副本分散到不同 UDP relay port。

当前推荐策略：

```text
默认继续保持 spreadRedundantCopies=false
先压测 RSS_RPC relay group + 逻辑包级 relay 轮换
如果仍存在单 relay port 黑洞、端口级限速或游戏/语音抖动，再实现 shared dedup
```

收益评级：

```text
普通场景：中等
强端口/五元组限速场景：较高
纯链路拥塞场景：一般
```

实现成本评级：

```text
仅 B 端 inbound shared dedup：中等，约 1~2 天
双向 shared dedup：中等偏高，约 2~4 天
加权/质量感知副本分布：偏高，约 4~7 天
兼容标准 SOCKS5 多 control 模式：不建议
```

### 2. 当前模式下为什么收益有限

当前端口跳跃和 RDNT 联用时，推荐语义是：

```text
逻辑包 #1 的 N 个 RDNT 副本 -> relayPortA
逻辑包 #2 的 N 个 RDNT 副本 -> relayPortB
逻辑包 #3 的 N 个 RDNT 副本 -> relayPortC
```

这种方式已经能把不同逻辑包分摊到多个 relay port，降低单端口持续流量，同时不会让 Proxy B 把同一个逻辑包重复转发到真实目标。

在这种模式下，同一个 RDNT 组没有跨 relay，因此跨 relay shared dedup 几乎没有直接收益。

也就是说，只要保持：

```java
spreadRedundantCopies = false;
```

就可以暂缓实现 shared dedup。

### 3. shared dedup 的真正收益

shared dedup 的核心意义是允许：

```text
逻辑包 #1 副本1 -> relayPortA
逻辑包 #1 副本2 -> relayPortB
逻辑包 #1 副本3 -> relayPortC
```

Proxy B 多个 relay port 收到同一个 RDNT seq 后，只把第一份新包转发给真实目标，后续副本被 group 级去重窗口丢弃。

#### 3.1 抗单 relay port 丢包更强

如果某个 UDP relay port 被限速、丢包、QoS、NAT 映射异常或局部黑洞，当前“同 RDNT 组走同一个 relay”的模式下，同一个逻辑包的所有副本可能一起失败。

跨 relay 后：

```text
relayPortA 丢了
relayPortB 可能到
relayPortC 可能到
```

这对游戏、语音、弱网 UDP 更有价值。

#### 3.2 从流量分散升级为单包冗余

当前端口跳跃主要是“包级轮换”：

```text
不同逻辑包分散到不同 relay
```

shared dedup 后才是“副本级分散”：

```text
同一个逻辑包的多个副本分散到不同 relay
```

如果链路问题与端口或五元组相关，副本级分散比包级轮换更有效。

#### 3.3 端口级限速场景收益更高

如果中间设备、运营商、NAT 或防火墙存在按 UDP 端口/五元组限速，shared dedup 可以让同一逻辑包的多份副本跨端口走，降低单端口瞬时失败概率。

但如果丢包来自以下原因，收益可能有限：

```text
公网链路整体拥塞
国际出口丢包
VPS 带宽打满
机器 CPU 或网卡软中断打满
```

这些情况下多个 relay port 仍然共享同一条物理路径，跨端口副本不一定能显著提高到达率。

### 4. 实现成本来源

当前 RDNT 去重模型是 channel 级的。

`UdpRedundantDecoder` 每个 channel 持有独立去重状态，并且按 sender 地址维护窗口。若同一个 RDNT seq 的副本进入 Proxy B 的不同 UDP relay channel，每个 decoder 都可能认为它是首次包。

所以 shared dedup 不能简单把 handler 标记为 `@Sharable`，需要把去重窗口提升到 `UdpRelayGroup` 级别。

#### 4.1 去重窗口从 channel 级迁移到 group 级

当前模型：

```text
relayChannelA -> decoderA -> windows
relayChannelB -> decoderB -> windows
relayChannelC -> decoderC -> windows
```

目标模型：

```text
UdpRelayGroup
  -> sharedDedupWindow
      relayChannelA / relayChannelB / relayChannelC 共用
```

#### 4.2 去重 key 需要重新设计

建议 key：

```text
groupId + direction + destAddr + seqId
```

字段含义：

- `groupId`：RSS RPC relay group 标识，避免不同 group 的 seq 冲突。
- `direction`：方向，至少区分 A_TO_B 与 B_TO_A，避免双向 RDNT seq 空间互相污染。
- `destAddr`：SOCKS5 UDP header 内的真实目标地址，避免未来一个 group 复用多个目标时误丢包。
- `seqId`：RDNT header 中的 sequence id。

如果确认一个 Proxy A outbound channel 的 seqId 对所有目标全局递增，也可以简化为：

```text
groupId + direction + seqId
```

但默认建议保留 `destAddr`，更稳。

#### 4.3 dedup 位置需要调整

为了生成 `destAddr` 维度的 key，Proxy B 在执行 shared dedup 时需要同时读取：

```text
RDNT header: magic + seqId
SOCKS5 UDP header: ATYP + DST.ADDR + DST.PORT
```

推荐处理顺序：

```text
收到 UDP datagram
  -> 判断 RDNT magic
  -> 读取 seqId，但不要破坏下游 readerIndex
  -> peek SOCKS5 UDP header，解析 destAddr
  -> group shared dedup check
  -> 新包：strip RDNT header 后继续转发
  -> 重复包：释放并丢弃
```

#### 4.4 生命周期和内存清理

shared dedup window 必须绑定 group 生命周期：

```text
open group -> 创建 shared dedup window
close group / idle timeout -> 清空 shared dedup window
```

避免 group 多、route 多时窗口状态残留。

窗口本身可以继续使用 64-bit sliding bitmap，内存成本可控。

### 5. 推荐启用条件

只建议在 RSS RPC relay group 模式下启用。

启用条件：

```text
udpRelayControlMode = RSS_RPC
或 AUTO 命中 RSS_RPC

capability 支持 UDP_RELAY_GROUP_SHARED_DEDUP
udpRedundantMultiplier > 1
spreadRedundantCopies = true
```

不满足条件时强制保持：

```java
spreadRedundantCopies = false;
```

不建议在标准 SOCKS5 兼容模式中实现跨 relay shared dedup。原因是标准模式下每个 hop 的控制面和生命周期是独立的，硬做 shared dedup 会增加大量状态桥接成本，收益不如 RSS RPC group 清晰。

### 6. 推荐配置

默认关闭：

```java
bConf.setUdpPortHoppingSpreadRedundantCopies(false);
bConf.setUdpRelayGroupSharedDedupEnabled(false);
```

仅在确认存在端口级限速或单 relay port 黑洞后开启：

```java
bConf.setUdpRelayControlMode(UdpRelayControlMode.AUTO);
bConf.setUdpPortHoppingEnabled(true);
bConf.setUdpRedundantMultiplier(2);
bConf.setUdpPortHoppingSpreadRedundantCopies(true);
bConf.setUdpRelayGroupSharedDedupEnabled(true);
```

建议增加保护：

```text
如果 spreadRedundantCopies=true 但 peer capability 不支持 UDP_RELAY_GROUP_SHARED_DEDUP：
  - 默认降级为 spreadRedundantCopies=false
  - 输出 warn 日志
  - 不让真实目标收到重复 payload
```

### 7. 分阶段实现建议

#### 7.1 第一版：仅 B 端 inbound shared dedup

目标：解决“同一个 RDNT 组副本跨 relay 后，Proxy B 重复转发到真实目标”的问题。

新增结构：

```java
final class UdpRelayGroup {
    final SharedUdpDedupWindow inboundDedup;
}
```

核心行为：

```text
relayPortA 收到 seq=100 -> 新包，转发真实目标
relayPortB 收到 seq=100 -> 重复，丢弃
relayPortC 收到 seq=100 -> 重复，丢弃
```

第一版只处理 A_TO_B 方向即可支撑 `spreadRedundantCopies=true` 的主要收益。

#### 7.2 第二版：双向 shared dedup

如果 B->A 回包方向也启用了 RDNT 并需要跨 relay 分散，再补双向去重。

key：

```text
groupId + direction + srcAddr/destAddr + seqId
```

#### 7.3 第三版：质量感知副本分布

引入 relay 权重：

```text
relayPortA 丢包低 -> 权重高
relayPortB 丢包高 -> 权重低
relayPortC 最近失败 -> 暂停发送
```

这是高阶优化，不建议第一版就做。

### 8. 数据结构草案

```java
final class SharedUdpDedupWindow {
    private final ConcurrentHashMap<DedupKey, DeduplicationWindow> windows;

    boolean checkAndMark(DedupKey key, long seqId);
    void cleanupStale(long nowNanos);
    void clear();
}

final class DedupKey {
    final String groupId;
    final byte direction;
    final InetSocketAddress endpoint;
}
```

`DeduplicationWindow` 可以复用当前 `UdpRedundantDecoder` 的 64-bit bitmap 思路：

```text
highestSeq
bitmap
lastAccessNanos
```

注意：`seqId` 不要放入 `DedupKey` 对象字段中，否则每个包都会产生新 key；应当由 `DeduplicationWindow.checkAndMark(seqId)` 处理。

### 9. 发送策略草案

当 `spreadRedundantCopies=false`：

```text
同一个逻辑包的所有 RDNT 副本 -> 同一个 selectedRelayAddr
```

当 `spreadRedundantCopies=true` 且 shared dedup 可用：

```text
副本1 -> relayPortA
副本2 -> relayPortB
副本3 -> relayPortC
```

选择方式：

```text
copyIndex=0: group.selectRelayAddress()
copyIndex=1: group.selectNextRelayAddressAvoidSame()
copyIndex=2: group.selectNextRelayAddressAvoidSame()
```

如果 active relay 数小于 multiplier，则允许部分副本复用 relay，但仍需保证 B 端 shared dedup 正常工作。

### 10. 指标建议

新增 metrics：

```text
socks.udp.relay.group.dedup.check.count
socks.udp.relay.group.dedup.duplicate.count
socks.udp.relay.group.dedup.unique.count
socks.udp.relay.group.dedup.window.count
socks.udp.relay.group.dedup.cleanup.count
socks.udp.relay.group.spread.copy.count
socks.udp.relay.group.spread.fallback.count
```

关键观测：

```text
duplicate / check 比例
spread 后端到端丢包率是否下降
真实 UDP 目标是否仍只收到一份 payload
shared dedup window 是否随 group close 清理
```

### 11. 测试计划

#### 11.1 单元测试

```text
SharedUdpDedupWindowTest
UdpRelayGroupSharedDedupTest
UdpRedundantSpreadAcrossRelaysTest
```

覆盖：

- 同一 `groupId + direction + destAddr + seqId` 只通过一次。
- 不同 `destAddr` 的相同 seqId 不互相误丢。
- 不同 `direction` 的相同 seqId 不互相误丢。
- 不同 `groupId` 的相同 seqId 不互相误丢。
- 过期窗口可清理。
- group close 后 dedup window 清空。

#### 11.2 集成测试

新增场景：

```text
ShadowsocksClient -> ShadowsocksServer -> Proxy A(rxlib)
  -> RSS_RPC relay group with spreadRedundantCopies=true
  -> Proxy B(rxlib)
  -> UDP echo dest
```

验证：

- A->B 抓包看到同一 RDNT seq 的副本分散到多个 relay port。
- Proxy B 的真实 UDP echo 目标只收到一份业务 payload。
- relayPortA 丢包或关闭时，同一逻辑包仍可能通过 relayPortB/relayPortC 到达。
- peer 不支持 `UDP_RELAY_GROUP_SHARED_DEDUP` 时自动降级为 `spreadRedundantCopies=false`。

### 12. 风险点

- dedup key 如果不包含 direction，双向 RDNT 可能互相污染。
- dedup key 如果不包含 destAddr，未来多目标复用 group 时可能误丢不同目标的包。
- 在 ByteBuf readerIndex 处理上必须谨慎，peek RDNT 和 SOCKS5 header 后要保证下游解析一致。
- shared dedup window 必须跟随 group close / idle timeout 清理，避免状态泄漏。
- 不能在未确认 peer 支持 shared dedup 时开启 `spreadRedundantCopies`，否则真实目标可能收到重复业务 payload。

### 13. 最终建议

短期：

```text
继续保持 spreadRedundantCopies=false
优先压测 RSS_RPC relay group 的控制面收益和端口分散收益
```

中期：

```text
如果存在单 relay port 黑洞、端口级限速或游戏/语音仍明显抖动，再实现 B 端 inbound shared dedup
```

长期：

```text
再做双向 shared dedup、relay 权重和质量感知副本分布
```

一句话：跨 relay RDNT 共享去重窗口有价值，但它是“弱网/端口级限速增强项”，不是 RSS RPC relay group 的主收益来源。只有当 `spreadRedundantCopies=true` 真正要上线时，才建议实现并默认只在 RSS RPC relay group capability 明确支持时启用。
