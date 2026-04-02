# RXlib Agent Guide

## Project Overview
**RXlib** (℞lib) is a comprehensive Java utility library (v2.21.11-SNAPSHOT) providing core functionality for distributed systems, networking, and concurrency. It's a multi-module Maven project with two active modules: `rxlib` (core library) and `rxlib-x` (extended features).

Key capabilities:
- **Dynamic thread pool** with optimal thread sizing (CPU-aware)
- **Network protocols**: SOCKS5 proxy, HTTP server/client, DNS server/client, RPC (Remoting), UDP
- **Distributed**: ID generation, entity database with sharding, name server
- **Data structures**: KV store (WAL+MMAP), hybrid streams, caching (Caffeine)
- **Cryptography**: Cipher support, SSL/TLS via Netty
- **Application server**: `Main.java` is a SOCKS5 proxy + shadow socks application

## Build & Test
```bash
# Maven builds both modules; skip tests by default (-Dmaven.test.skip=true in root pom.xml)
mvn clean install                    # Builds rxlib + rxlib-x
mvn -pl rxlib test                   # Run tests for core module only
mvn -pl rxlib -Dtest=ThreadPoolTest  # Run specific test
```

Tests use **JUnit 5** with `@Test` annotations. Base class `AbstractTester` provides utilities: `invoke()` for sync perf testing, `invokeAsync()` for concurrent testing. Output saved to `./target/`.

## Critical Architecture Patterns

### 1. **Thread Pool & Task Execution** (`Tasks`, `ThreadPool`, `RunFlag`)
- **Entry point**: `Tasks.pool()` returns singleton `ThreadPool` instance
- **Execution modes** (flags):
  - `SINGLE`: Skip execution if taskId already running (one active per taskId)
  - `SYNCHRONIZED`: Queue & wait if taskId already running
  - `TRANSFER`: Block caller until executed or queued
  - `PRIORITY`: Create new thread if pool/queue full
  - `THREAD_TRACE`: Enable async tracing (supports Timer, CompletableFuture)
  - `INHERIT_THREAD_LOCALS`: Child inherits parent's FastThreadLocal
  
```java
Tasks.pool().run(() -> { /*work*/ }, taskId, RunFlag.SINGLE.flags());
Tasks.schedulePeriod(action, delayMillis);  // Periodic execution
```

### 2. **Configuration & Watchers** (`YamlConfiguration`, `RSSConf`)
- Config files are watched for changes; listeners notified via `onChanged` event
- Example: `new YamlConfiguration("conf.yml").enableWatch()` in `Main.launchClient()`
- Configs can be read as POJOs: `.readAs(RSSConf.class)`
- RSSConf = "RSS Configuration" (app-specific SOCKS server config)

### 3. **Networking: RPC & Proxying**
- **Remoting**: Lightweight object RPC over TCP/RUDP
  - `Remoting.createFacade(Interface.class, RpcClientConfig)` = client proxy
  - `Remoting.register(impl, RpcServerConfig)` = server registration
  - Config modes: `poolMode()` (conn pool) vs `statefulMode()` (persistent conn)
  - Transport flags: `TransportFlags.GFW` (obfuscation), `CIPHER_BOTH`, `COMPRESS_BOTH`, `CLIENT_HTTP_PSEUDO_*`

- **SOCKS Proxy**: `SocksProxyServer`, `SocksConfig`, `SocksContext`
  - Route handlers: `onTcpRoute`, `onUdpRoute` accept `TripleAction<SocksProxyServer, SocksContext>`
  - Upstream selection via `BiFunc<SocksContext, UpstreamSupport>` (load balancing)
  - DNS interception via `DnsServer` with custom resolvers

### 4. **Event-Driven Routing** (`EventPublisher`, `Upstream`)
- Components use event handlers: `inSvr.onTcpRoute.replace(handler)` (replace previous), `.combine()` (chain)
- `SocksContext` contains source, destination, upstream route
- `UnresolvedEndpoint` = host/port pair; resolved at connection time
- `UpstreamSupport` = weighted server in round-robin list with optional RPC facade

### 5. **Type Conversion & Reflection** (`Reflects`, `Sys`)
- `Reflects.convertQuietly(obj, Class, defaultValue)` = safe type conversion
- `Sys.deepClone(obj)` = deep copy
- `Sys.mainOptions(args)` = parse `--key=value` arguments
- `Sys.toJsonString(obj)` = Fastjson2 serialization
- YAML + JSON parsing built-in

### 6. **Utilities: Collections & Streams**
- **Linq** = LINQ-to-Objects queries: `Linq.from(list).select(...).where(...).toList()`
- **RandomList** = weighted random selection (used for server load balancing)
- **Arrays** = utility methods (e.g., `toList()`, nullsafe operations)
- **IOStream** = read/write helpers: `readString(stream, charset)`

### 7. **Exception Handling & Tracing**
- `TraceHandler.INSTANCE` = exception trace store (queryable by date/type)
- Logged via SLF4J (Logback backend)
- HTTP endpoint `/traces` exposes exceptions in JSON

## Key Files & Module Organization

```
rxlib/src/main/java/org/rx/
├── core/          # ThreadPool, Tasks, Reflects, Sys, Linq, EventBus, Cache, IOC
├── bean/          # DTOs: DateTime, Tuple, RandomList, etc.
├── net/
│   ├── rpc/       # Remoting, RpcClientConfig, RpcServerConfig
│   ├── http/      # HttpServer, HttpClient, RestClient
│   ├── dns/       # DnsServer, DnsClient
│   ├── socks/     # SocksProxyServer, SocksConfig, SocksContext, ShadowsocksServer
│   └── transport/ # TcpClientConfig, TcpServerConfig, UdpClient
├── io/            # IOStream, Files, HybridStream
├── codec/         # CodecUtil (hash, cipher, codec)
└── util/          # Strings, Numbers, functional interfaces (Action, Func, etc.)

Main.java = Executable SOCKS proxy app (see launchClient, launchServer)
```

## Development Practices

1. **Lombok**: Use `@Slf4j`, `@Getter/@Setter`, `@RequiredArgsConstructor`, `@SneakyThrows`
2. **Functional patterns**: Interfaces like `Action`, `BiFunc<T, R>`, `TripleAction<T, U, V>`
3. **Disposable pattern**: Classes extend `Disposable` for resource cleanup
4. **Config-driven**: Many features accept config objects (e.g., `TcpClientConfig`, `HttpServer(port, true)`)
5. **Async-first**: Most I/O uses Netty; callbacks/futures common; avoid blocking

## Build Quirks & Deployment
- Version: `2.21.11-SNAPSHOT` (published to Maven Central via SOSSRh)
- Java 1.8+ (bytecode compiled to 1.8)
- Excludes Netty codecs: haproxy, memcache, mqtt, redis, stomp (reduce JAR size)
- GPG signing enabled for releases (configure `~/.m2/settings.xml` with `ossrh` credentials)
- Modules `agent` and `daemon` are disabled in parent `<modules>` but still present

## Testing Patterns
- Perf benchmarks use JMH (`RxBenchmark.java`)
- Integration tests often start servers on dynamic ports; see `SocksProxyServerIntegrationTest`
- Test utilities in `AbstractTester`: `path()` for file output, `invoke()`/`invokeAsync()` for metrics
- Use `ResetEventWait` for synchronization in async tests

## Common Gotchas
- **FastThreadLocal**: Used extensively; child threads don't inherit unless `INHERIT_THREAD_LOCALS` flag set
- **Config watching**: Changes detected asynchronously; listeners run in event thread
- **Port allocation**: App uses `port` for SOCKS, `port+1` for RPC server (hardcoded in `Main`)
- **Cipher mode detection**: Automatic via `TransportFlags`; requires correct flag combo (client ↔ server must match)

