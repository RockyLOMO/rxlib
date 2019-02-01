package org.rx.fl.service.media;

import com.github.monkeywie.proxyee.intercept.HttpProxyIntercept;
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptInitializer;
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptPipeline;
import com.github.monkeywie.proxyee.server.HttpProxyServer;
import com.github.monkeywie.proxyee.server.HttpProxyServerConfig;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.common.Disposable;
import org.rx.fl.util.AwtBot;
import org.rx.fl.util.HttpCaller;
import org.rx.util.ManualResetEvent;

import java.awt.image.BufferedImage;
import java.util.concurrent.Future;

import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public final class JdLogin extends Disposable {
    private static final BufferedImage jdKey, jdKey2;

    static {
        Class owner = JdLogin.class;
        jdKey = AwtBot.getImageFromResource(owner, "/static/jdKey.png");
        jdKey2 = AwtBot.getImageFromResource(owner, "/static/jdKey2.png");
    }

    private String loginKey;
    private ManualResetEvent waiter;
    private Future future;
    private AwtBot bot;
    private HttpProxyServer proxyServer;

    public JdLogin(int port) {
        waiter = new ManualResetEvent();
        bot = new AwtBot();
        TaskFactory.run(() -> listen(port));
    }

    @Override
    protected void freeObjects() {
        proxyServer.close();
    }

    private void listen(int port) {
        HttpProxyServerConfig config = new HttpProxyServerConfig();
        config.setHandleSsl(false);
        proxyServer = new HttpProxyServer().serverConfig(config)
                .proxyInterceptInitializer(new HttpProxyInterceptInitializer() {
                    @Override
                    public void init(HttpProxyInterceptPipeline pipeline) {
                        pipeline.addLast(new HttpProxyIntercept() {
                            @Override
                            public void beforeRequest(Channel clientChannel, HttpRequest httpRequest, HttpProxyInterceptPipeline pipeline) throws Exception {
                                String host = httpRequest.headers().get(HttpHeaderNames.HOST);
                                if (host != null) {
                                    String url;
                                    if (httpRequest.uri().indexOf("/") == 0) {
                                        if (httpRequest.uri().length() > 1) {
                                            url = host + httpRequest.uri();
                                        } else {
                                            url = host;
                                        }
                                    } else {
                                        url = httpRequest.uri();
                                    }
                                    log.info("Request: {}", url);
                                    if (url.startsWith("passport.jd.com/uc/nplogin?")) {
                                        log.info("JdLogin detect {}", url);
                                        loginKey = "http://" + url;
                                        waiter.setThenReset(3000);
                                        clientChannel.close();
                                        return;
                                    }
                                }

                                httpRequest.headers().set(HttpHeaderNames.USER_AGENT, HttpCaller.IE_UserAgent);
                                pipeline.beforeRequest(clientChannel, httpRequest);
                            }

                            @Override
                            public void afterResponse(Channel clientChannel, Channel proxyChannel, HttpResponse httpResponse, HttpProxyInterceptPipeline pipeline) throws Exception {
                                httpResponse.headers().add("intercept", "rxProxy");
                                pipeline.afterResponse(clientChannel, proxyChannel, httpResponse);
                            }
                        });
                    }
                });
        proxyServer.start(port);
    }

    @SneakyThrows
    public synchronized String produceKey() {
        if (future != null) {
            future.cancel(true);
        }

        bot.clickByImage(jdKey);
        log.info("step1 clickByImage ok");

        waiter.waitOne(6 * 1000);
        log.info("step2 wait proxy callback");

        try {
            log.info("step2 get key {}", loginKey);
            return loginKey;
        } finally {
            loginKey = null;
            future = TaskFactory.schedule(() -> {
                log.info("step3 try close it");
                bot.clickByImage(jdKey2);
                log.info("step3 closed it");
                future.cancel(true);
                future = null;
            }, 2 * 1000);
        }
    }
}
