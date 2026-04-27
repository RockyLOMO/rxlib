# HTTP 模块 (org.rx.net.http)

提供轻量级、高性能的 HTTP 客户端与服务端实现。虽然业务开发通常倾向于使用 Spring Boot，但此模块用于那些对性能和开销有极致要求的场景。

## 核心类介绍

- **`HttpClient`**:
  核心的 HTTP 客户端，支持同步/异步调用、长连接复用（Keep-Alive）、流式文件上传/下载以及代理（SOCKS5/HTTP Proxy）配置。

- **`HttpServer`**:
  轻量级的 Netty HTTP 服务器。可以利用其内置的注解支持或处理器映射快速启动 RESTful 服务或静态文件服务器。

- **`RestClient`**:
  建立在 `HttpClient` 之上，提供类似 Retrofit / Feign 的声明式接口调用能力。

- **`HttpClientCache`**:
  与 `HttpClient` 集成，提供了类似浏览器或 OKHttp 的 HTTP 缓存机制（控制 Cache-Control, ETag 等），以减少冗余网络请求。

- **`HttpClientCookieJar`**:
  实现了 Cookie 的自动管理和持久化，用于保持会话状态（如自动带上 JSESSIONID 访问连续接口）。

- **`ServerRequest` / `ServerResponse`**:
  对应于 `HttpServer` 处理中的请求和响应上下文，封装了 Netty 原生的 `FullHttpRequest` / `HttpResponse` 以便于业务提取参数和写入响应。

## 适用场景
- 构建需要极大并发的 HTTP 反向代理或微型网关。
- 业务代码之外，底层系统框架自身需要的轻量级 Web 接口交互或状态拉取。
