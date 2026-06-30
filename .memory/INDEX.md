# rxlib 架构演进与优化记忆 (MEMORY)

本文件整理并汇总了历史所有的优化计划与审查文档，旨在归纳 rxlib（高性能网络库）在核心架构、网络层、线程模型、资源池及监控体系上的设计决策记录（ADR）和历史演进明细。

## 项目核心定位与性能基准 (Core Principles)

**当前模式：高性能模式 (High-Performance Netty/NIO)**

xlib 的开发和演进必须坚守以下底层性能红线：
- **热点路径零分配 (Zero Allocation)**：数据处理避免重复分配。使用池化（PooledByteBufAllocator）、复用内存结构。
- **无反射调用 (Zero Reflection on Hot Path)**：关键路径必须在初始化阶段完成反射缓存，热点访问时绝对不允许调用耗时反射 API。
- **无锁与细粒度并发控制 (Lock-free & CAS)**：坚决避免不必要的 synchronized，使用 LongAdder、CAS、Atomic 协议和 Promise 模型。
- **严格的内存生命周期管控**：核心组件防范强引用泄漏。清理失败遗留对象，防范流量激增导致 OOM（强制 Backpressure 背压）。

---

## 历史档案记录 (按时间升序)
由于原始的计划文件已被归档删除，此处保留了当时所有的上下文、核心审查结论以及 TODO 列表。
## 模块索引 (Module Index)
本目录将所有架构演进历史按照**业务领域 (Domain)** 进行了拆分，以提供最精确的上下文（Context）。
- [网络与代理层 (Network & Proxy)](./network_and_proxy.md)
- [线程与定时器层 (Thread & Timer)](./thread_and_timer.md)
- [缓存与数据库系统 (Cache & DB)](./cache_and_db.md)
- [资源与内存池 (Memory & Pool)](./memory_and_pool.md)
- [监控与可观测性 (Monitoring)](./monitoring.md)
- [核心基础组件与工具 (Core Utils)](./core_utils.md)

