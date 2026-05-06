# 排除的代码与包清单 (Excluded Code & Packages)

在进行代码评审、性能分析或架构调整时，以下类和包属于已被评估并明确排除在外的对象。

---

## 🚫 排除类 (Excluded Classes)

以下类由于包含历史设计、处于实验阶段或有特定的外部依赖，已从核心评审和活跃开发范围中排除：

### 📂 IO 与存储模块 (`org.rx.io.*`)
*   [CompositeLock](../../rxlib/src/main/java/org/rx/io/CompositeLock.java) — 组合锁实现。
*   [CompositeMmap](../../rxlib/src/main/java/org/rx/io/CompositeMmap.java) — 组合内存映射文件。
*   [ExternalSortingIndexer](../../rxlib/src/main/java/org/rx/io/ExternalSortingIndexer.java) — 外部排序索引器。
*   [HashKeyIndexer](../../rxlib/src/main/java/org/rx/io/HashKeyIndexer.java) — 哈希键索引器。
*   [KeyIndexer](../../rxlib/src/main/java/org/rx/io/KeyIndexer.java) — 键索引器基类。
*   [KeyValueStore](../../rxlib/src/main/java/org/rx/io/KeyValueStore.java) — 基础 KV 存储器实现。
*   [KeyValueStoreConfig](../../rxlib/src/main/java/org/rx/io/KeyValueStoreConfig.java) — KV 存储配置项。
*   [ShardingEntityDatabase](../../rxlib/src/main/java/org/rx/io/ShardingEntityDatabase.java) — 分片实体数据库。
*   [WALFileStream](../../rxlib/src/main/java/org/rx/io/WALFileStream.java) — WAL（预写日志）文件流。

### 📂 网络与传输模块 (`org.rx.net.*`)
*   [FecConfig](../../rxlib/src/main/java/org/rx/net/FecConfig.java) — FEC（前向纠错）配置项。
*   [FecDecoder](../../rxlib/src/main/java/org/rx/net/FecDecoder.java) — FEC 解码器。
*   [FecEncoder](../../rxlib/src/main/java/org/rx/net/FecEncoder.java) — FEC 编码器。
*   [FecPacket](../../rxlib/src/main/java/org/rx/net/FecPacket.java) — FEC 数据包定义。
*   [FecUdpClient](../../rxlib/src/main/java/org/rx/net/FecUdpClient.java) — 基于 FEC 的 UDP 客户端。

---

## 🚫 排除包 (Excluded Packages)

以下包包含第三方集成或不再活跃使用的历史底层传输实现：

*   [org.rx.third](../../rxlib/src/main/java/org/rx/third) — 外部第三方工具或未改动的库占位类。
*   [org.rx.net.socks.httptunnel](../../rxlib/src/main/java/org/rx/net/socks/httptunnel) — 历史 HTTP 隧道底层实现。