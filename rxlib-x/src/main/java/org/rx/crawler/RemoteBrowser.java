package org.rx.crawler;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.FlagsEnum;
import org.rx.core.Linq;
import org.rx.core.Reflects;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.util.Snowflake;
import org.springframework.service.MiddlewareConfig;
import org.springframework.service.SpringContext;
import org.rx.util.function.*;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Predicate;

import static org.rx.core.Extends.require;
import static org.rx.core.Extends.sleep;
import static org.rx.core.Sys.proxy;

@Slf4j
public abstract class RemoteBrowser implements Browser {
    private static final ConcurrentHashMap<String, BrowserPoolListener> FACADE = new ConcurrentHashMap<>();

    public static Future<?> invokeAsync(BiAction<RemoteBrowser> callback, String cookieRegion) {
        return invokeAsync(callback, cookieRegion, Thread.NORM_PRIORITY);
    }

    public static Future<?> invokeAsync(BiAction<RemoteBrowser> callback, String cookieRegion, int priority) {
        BrowserAsyncTopic asyncTopic = SpringContext.getBean(BrowserAsyncTopic.class);

        long asyncId = Snowflake.DEFAULT.nextId();
        Future<?> future = asyncTopic.listen(asyncId, callback);
        asyncTopic.add(new BrowserAsyncRequest(asyncId, priority, cookieRegion));
        return future;
    }

    public static <T> Future<T> invokeAsync(BiFunc<RemoteBrowser, T> callback, String cookieRegion) {
        return invokeAsync(callback, cookieRegion, Thread.NORM_PRIORITY);
    }

    public static <T> Future<T> invokeAsync(BiFunc<RemoteBrowser, T> callback, String cookieRegion, int priority) {
        BrowserAsyncTopic asyncTopic = SpringContext.getBean(BrowserAsyncTopic.class);

        long asyncId = Snowflake.DEFAULT.nextId();
        Future<T> future = asyncTopic.listen(asyncId, callback);
        asyncTopic.add(new BrowserAsyncRequest(asyncId, priority, cookieRegion));
        return future;
    }

    public static String buildCookieRegion(@NonNull String name, @NonNull FlagsEnum<RegionFlags> flags) {
        return String.format("%s_%s", name, flags.getValue());
    }

    public static void invoke(BiAction<RemoteBrowser> consumer) {
        invoke(consumer, null);
    }

    public static void invoke(BiAction<RemoteBrowser> consumer, String cookieRegion) {
        invoke(consumer, cookieRegion, BrowserType.CHROME);
    }

    @SneakyThrows
    public static void invoke(@NonNull BiAction<RemoteBrowser> consumer, String cookieRegion, BrowserType type) {
        try (RemoteBrowser browser = create(type)) {
            browser.setCookieRegion(cookieRegion);
            consumer.invoke(browser);
        }
    }

    public static <T> T invoke(BiFunc<RemoteBrowser, T> consumer) {
        return invoke(consumer, null);
    }

    public static <T> T invoke(BiFunc<RemoteBrowser, T> consumer, String cookieRegion) {
        return invoke(consumer, cookieRegion, BrowserType.CHROME);
    }

    @SneakyThrows
    public static <T> T invoke(@NonNull BiFunc<RemoteBrowser, T> consumer, String cookieRegion, BrowserType type) {
        //Tasks的CompletableFuture timeout时内容会继续执行会出现不一致情况，
        //故暂时RemoteBrowser控制timeout
        try (RemoteBrowser browser = create(type)) {
            browser.setCookieRegion(cookieRegion);
            return consumer.invoke(browser);
        }
    }

    public static synchronized RemoteBrowser create(@NonNull BrowserType type) {
        MiddlewareConfig config = SpringContext.getBean(MiddlewareConfig.class);
        String endpoint = config.getCrawlerEndpoint();
        BrowserPoolListener listener = FACADE.computeIfAbsent(endpoint, k -> Remoting.createFacade(BrowserPoolListener.class, RpcClientConfig.statefulMode(endpoint, 0)));
        int port = listener.nextIdleId(type);
        InetSocketAddress newEndpoint = Sockets.newEndpoint(endpoint, port);
        log.info("RBrowser connect {} -> {}[{}]", type, newEndpoint, endpoint);
        return wrap(newEndpoint);
    }

    static RemoteBrowser wrap(InetSocketAddress endpoint) {
        RpcClientConfig<Browser> clientConfig = RpcClientConfig.statefulMode(endpoint, 0);
        clientConfig.getTcpConfig().setEnableReconnect(false);
        Browser browser = Remoting.createFacade(Browser.class, clientConfig);
        return proxy(RemoteBrowser.class, (m, p) -> {
            if (Reflects.isCloseMethod(m)) {
                log.debug("RBrowser release {}", browser);
                browser.close();
                return null;
            }
            if (Linq.from("createWait", "navigateBlank", "invoke", "invokeMaximize", "waitScriptComplete", "waitClickComplete").contains(m.getName())) {
                return p.fastInvokeSuper();
            }
            return p.fastInvoke(browser);
        });
    }

    @SneakyThrows
    public void invokeMaximize(Action consumer) {
        maximize();
        try {
            consumer.invoke();
        } finally {
            normalize();
        }
    }

    @SneakyThrows
    public <T> T invokeMaximize(Func<T> consumer) {
        maximize();
        try {
            return consumer.invoke();
        } finally {
            normalize();
        }
    }

    //element 不可见时用click()
    public <T> T waitScriptComplete(int timeoutSeconds, @NonNull String checkCompleteScript, String callbackScript) {
        executeScript(String.format("_rx.waitComplete(%s, function () { %s });", timeoutSeconds, checkCompleteScript));

        long waitMillis = getWaitMillis();
        int count = 0, loopCount = Math.round(timeoutSeconds * 1000f / waitMillis);
        do {
            String isOk = executeScript("return _rx.ok;");
            if ("1".equals(isOk)) {
                break;
            }
            sleep(waitMillis);
        }
        while (count++ < loopCount);
        return executeScript(callbackScript);
    }

    public boolean waitClickComplete(int timeoutSeconds, @NonNull Predicate<Integer> checkComplete, @NonNull String btnSelector, int reClickEachSeconds, boolean skipFirstClick) {
        require(timeoutSeconds, timeoutSeconds <= 60);
        require(reClickEachSeconds, reClickEachSeconds > 0);

        return createWait(timeoutSeconds).retryEvery(reClickEachSeconds * 1000L, s -> {
            try {
                elementClick(btnSelector, true);
                log.debug("waitClickComplete {} click ok", btnSelector);
            } catch (InvalidException e) {
                log.info(e.getMessage());
            }
        }, !skipFirstClick).awaitTrue(s -> checkComplete.test(s.getEvaluatedCount()));
    }
}
