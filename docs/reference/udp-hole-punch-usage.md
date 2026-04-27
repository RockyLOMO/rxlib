# UDP 打洞模块简介与使用文档（未提交代码）

## 1. 模块简介

当前未提交代码新增了 `org.rx.net.punch` UDP 打洞能力，用于让两端 UDP 客户端在 NAT 场景下通过协调端完成地址交换并建立直连通道。

核心目标：

- 协调端只做会话登记和地址交换，不转发业务数据。
- 两个客户端在同一本地 UDP 端口上探测并建立可用的双向映射。
- 建立直连后，通过 `UdpHolePunchSession` 直接收发与 RPC 请求响应。

## 2. 代码清单

主代码：

- `rxlib/src/main/java/org/rx/net/punch/UdpHolePunchServer.java`
- `rxlib/src/main/java/org/rx/net/punch/UdpHolePunchClient.java`
- `rxlib/src/main/java/org/rx/net/punch/UdpHolePunchSession.java`
- `rxlib/src/main/java/org/rx/net/punch/UdpHolePunchRegistry.java`
- `rxlib/src/main/java/org/rx/net/punch/UdpHolePunchPackets.java`

测试代码：

- `rxlib/src/test/java/org/rx/net/punch/UdpHolePunchRegistryTest.java`
- `rxlib/src/test/java/org/rx/net/punch/UdpHolePunchIntegrationTest.java`

## 3. 交互流程（简化）

1. 双方客户端向协调端发送 `RendezvousRequest(roomId, peerId)`。
2. 协调端在 `UdpHolePunchRegistry` 中登记 `peerId -> observedEndpoint`，并返回 `RendezvousResponse`。
3. 双方拿到对端外网观察地址后，循环发送 `DirectProbe`。
4. 任一方收到对端 `DirectProbe` 后立即回 `DirectProbeAck`。
5. 任一方收到探测或 ACK 后，`directFuture` 完成，建立 `UdpHolePunchSession`。
6. 后续业务走 `session.send/request/reply` 直接点对点传输。

## 4. 服务端使用方式

### 4.1 启动

```java
UdpHolePunchServer server = new UdpHolePunchServer(41000);
InetSocketAddress endpoint = server.getLocalEndpoint();
```

可选构造参数：

- `bindPort`：监听端口。
- `sessionTtlMillis`：房间内 peer 记录 TTL。
- `cleanupIntervalMillis`：过期会话清理周期。
- `maxPeersPerRoom`：单房间最大 peer 数（当前默认 2）。

### 4.2 关闭

```java
server.close();
```

关闭时会移除接收监听、停止清理任务并关闭底层 `UdpClient`。

## 5. 客户端使用方式

### 5.1 创建客户端

```java
UdpHolePunchClient client = new UdpHolePunchClient(0); // 0 表示随机本地端口
```

或注入已有 `UdpClient`：

```java
UdpClient udp = new UdpClient(42001);
UdpHolePunchClient client = new UdpHolePunchClient(udp);
```

### 5.2 参数调优（按网络情况）

- `setPeerWaitTimeoutMillis(...)`：等待对端出现的总超时。
- `setDirectConnectTimeoutMillis(...)`：直连探测阶段超时。
- `setDirectProbeCount(...)`：探测包总数上限。
- `setDirectProbeIntervalMillis(...)`：探测轮询间隔。
- `setRendezvousPollIntervalMillis(...)`：向协调端轮询间隔。
- `setRendezvousRequestTimeoutMillis(...)`：单次协调请求超时。

### 5.3 建连

```java
InetSocketAddress serverEndpoint = new InetSocketAddress("1.2.3.4", 41000);
UdpHolePunchSession session = client.connect(serverEndpoint, "room-1", "peer-a");
```

说明：

- `roomId`：同房间 peer 才会匹配。
- `peerId`：房间内唯一标识，重复会覆盖同 peer 的最新地址。
- 成功后返回 `UdpHolePunchSession`，保存了对端直连地址。

## 6. 会话收发与 RPC

### 6.1 单向发送

```java
session.send("hello");
```

### 6.2 请求响应（RPC）

```java
String pong = session.request("ping", String.class, 3000);
```

### 6.3 被动收包并回复

```java
session.onReceive.combine((s, e) -> {
    Object packet = e.getValue().packet();
    if ("ping".equals(packet)) {
        s.reply(e.getValue(), "pong");
    }
});
```

## 7. 异常与边界行为

- 协调端房间满员时，`register` 抛出 `InvalidException`。
- `connect` 超时会抛 `TimeoutException`（等待对端或直连探测超时）。
- 客户端关闭后，后续调用会报 `UdpHolePunchClient closed`。
- 会话关闭后继续发送/请求会报 `Hole punch session ... is closed`。
- 若会话无直连地址会报 `has no direct endpoint`。

## 8. 已有测试覆盖

- `UdpHolePunchRegistryTest`
  - 首次注册等待状态
  - 双方匹配后互见对端地址
  - 过期清理与房间回收
  - 房间满员拒绝第三个 peer

- `UdpHolePunchIntegrationTest`
  - 双端并发 `connect`
  - 直连端口与回环地址校验
  - 直连请求响应链路校验（`direct-ping` / `direct-pong`）

## 9. 快速验证命令

在仓库根目录执行：

```bash
mvn "-Dtest=UdpHolePunchRegistryTest,UdpHolePunchIntegrationTest" test
```

若仅验证打洞端到端链路，也可只跑：

```bash
mvn "-Dtest=UdpHolePunchIntegrationTest" test
```

