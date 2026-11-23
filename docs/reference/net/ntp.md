# 时间同步模块 (org.rx.net.ntp)

基于网络时间协议（NTP）实现的时间同步服务。

## 核心类介绍

- **`NtpClient`**:
  执行 NTP 请求的客户端工具。向远程 NTP 服务器（如 `pool.ntp.org`）发起 UDP 时间同步请求，计算并纠正本地时间偏移（Offset）。

- **`NtpPacket`**:
  NTP 协议数据包格式（RFC 5905）的 POJO 和编解码载体。

- **`NtpResult` / `NtpClock`**:
  `NtpClient.getTime` 返回可关闭的 `NtpResult`。真正改时钟的是 `NtpClock`。`app.net.ntp.syncMode` 默认 0，不自动同步；bit 0 打开周期同步，bit 1 会改写 `currentTimeMillis`。默认服务器是 `ntp.aliyun.com`、`ntp.tencent.com`、`cn.pool.ntp.org`。

## 适用场景
- 需要分布式节点在内网中保持高度时间一致性（如分布式锁的超时判断或令牌桶限流算法）。
- 防止服务器本地时钟钟漂造成逻辑异常。
