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

---

## 使用示例

### RestClient

类似 FeignClient、连接池化的 Client。

1. 先定义接口,例如：
```java
@RequestMapping(value = "user", method = {RequestMethod.POST})
public interface UserContract extends RestTypeInvoker {  //RestTypeInvoker 用于处理复杂嵌套的泛型
    @RequestMapping("/saveUser")
    SaveUserResponse saveUser(@RequestBody SaveUserRequest request);

    @RequestMapping("/getUser")
    GetUserResponse getUser(@RequestBody GetUserRequest request);

    @RequestMapping("/withdraw")
    WithdrawResponse withdraw(@RequestBody WithdrawRequest request);

    @RequestMapping("/feedback")
    void feedback(@RequestParam("userId") String userId, @RequestParam("content") String content);

    @RequestMapping("/signIn")
    SignInResponse signIn(@RequestBody SignInRequest request);

    @RequestMapping("/checkSignIn")
    Map checkSignIn(@RequestParam("token") String token);
}
```

2. 在 Spring 里注册 Bean
```java
@Configuration
public class ApiClientConfig {
    @Resource
    private AppConfig appConfig;
    private final BiFunc<String, Boolean> checkResponse = responseText -> {
        if (Strings.isEmpty(responseText)) {
            return false;
        }
        Object parse = JSON.parse(responseText);
        if (parse instanceof JSONObject) {
            return ((JSONObject) parse).getIntValue("code") == RestResult.SuccessCode;
        }
        return true;
    };

    @Bean
    public MediaContract mediaContract() {
        return RestClient.facade(MediaContract.class, appConfig.getApiUrl(), checkResponse);
    }

    @Bean
    public UserContract userContract() {
        return RestClient.facade(UserContract.class, appConfig.getApiUrl(), checkResponse);
    }
}
```

3. 注入使用
```java
@Slf4j
@Service
public class UserService {
    @Resource
    private UserContract userClient;

    @Test
    public void restClient() {
        List<Tuple<String, List<Integer>>> result = userClient.invoke(() -> userClient.checkSignIn(), new TypeReference<List<Tuple<String, List<Integer>>>>() {
        }.getType());  //复杂泛型情况
    }
}
```

4. 连接池配置
```yml
app:
  bufferSize: 512
  netTimeoutMillis: 15000
  netMinPoolSize: 2
  netMaxPoolSize: 0  #默认 cpu * 2
```

### HttpServer

基于 Netty 实现的轻量级 Server。

```java
@SneakyThrows
@Test
public void httpServer() {
    ManualResetEvent wait = new ManualResetEvent();
    Map<String, Object> qs = new HashMap<>();
    qs.put("a", "1");
    qs.put("b", "乐之");

    Map<String, Object> f = new HashMap<>();
    f.put("a", "1");
    f.put("b", "乐之");

    Map<String, DuplexStream> fi = new HashMap<>();
    fi.put("a", DuplexStream.wrap("1.dat", new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));

    String j = "{\"a\":1,\"b\":\"乐之\"}";

    String hbody = "<html><body>hello world</body></html>";
    String jbody = "{\"code\":0,\"msg\":\"hello world\"}";

    HttpServer server = new HttpServer(8081, true);
    server.requestMapping("/api", (request, response) -> {
        MultiValueMap<String, String> queryString = request.getQueryString();
        for (Map.Entry<String, Object> entry : qs.entrySet()) {
            assert entry.getValue().equals(queryString.getFirst(entry.getKey()));
        }

        MultiValueMap<String, String> form = request.getForm();
        for (Map.Entry<String, Object> entry : f.entrySet()) {
            assert entry.getValue().equals(form.getFirst(entry.getKey()));
        }

        MultiValueMap<String, FileUpload> files = request.getFiles();
        for (Map.Entry<String, DuplexStream> entry : fi.entrySet()) {
            FileUpload fileUpload = files.getFirst(entry.getKey());
            try {
                Arrays.equals(fileUpload.get(), entry.getValue().toArray());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        response.htmlBody(hbody);
    }).requestMapping("/json", (request, response) -> {
        String json = request.jsonBody();
        assert j.equals(json);

        response.jsonBody(jbody);

        wait.set();
    });

    RxConfig.INSTANCE.setLogStrategy(LogStrategy.ALWAYS);
    HttpClient client = new HttpClient();
    client.setEnableLog(true);
    assert hbody.equals(client.post(HttpClient.buildUrl("https://127.0.0.1:8081/api", qs), f, fi).toString());

    String resJson = client.postJson("https://127.0.0.1:8081/json", j).toString();
    JSONObject jobj = client.postJson("https://127.0.0.1:8081/json", j).toJson();
    System.out.println(jobj);

    System.out.println(resJson);
    assert jbody.equals(resJson);

    wait.waitOne();
}
```
