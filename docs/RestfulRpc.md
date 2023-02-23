## RestfulRpcClient

类似FeignClient、连接池化的Client。

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

2. 在spring里注册bean
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

## HttpServer

基于netty实现。

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

    Map<String, IOStream> fi = new HashMap<>();
    fi.put("a", IOStream.wrap("1.dat", new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));

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
        for (Map.Entry<String, IOStream> entry : fi.entrySet()) {
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

