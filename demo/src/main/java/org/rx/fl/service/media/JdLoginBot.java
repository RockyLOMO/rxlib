package org.rx.fl.service.media;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.common.Disposable;
import org.rx.common.InvalidOperationException;
import org.rx.fl.util.AwtBot;
import org.rx.fl.util.HttpCaller;
import org.rx.fl.util.ImageUtil;
import org.rx.socks.proxyee.intercept.HttpProxyIntercept;
import org.rx.socks.proxyee.intercept.HttpProxyInterceptInitializer;
import org.rx.socks.proxyee.intercept.HttpProxyInterceptPipeline;
import org.rx.socks.proxyee.server.HttpProxyServer;
import org.rx.socks.proxyee.server.HttpProxyServerConfig;
import org.rx.util.ManualResetEvent;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public final class JdLoginBot extends Disposable {
    private static final BufferedImage jdKey;

    static {
        Class owner = JdLoginBot.class;
        jdKey = ImageUtil.getImageFromResource(owner, "/bot/jdKey.png");
    }

    private String loginKey;
    private ManualResetEvent waiter;
    private AwtBot bot;
    private Point lastPoint;
    private HttpProxyServer proxyServer;

    public JdLoginBot(int port) {
        waiter = new ManualResetEvent();
        bot = AwtBot.getBot();
        TaskFactory.run(() -> listen(port));
    }

    @Override
    protected void freeObjects() {
        proxyServer.close();
    }

    private void listen(int port) {
        HttpProxyServerConfig config = new HttpProxyServerConfig();
        config.setHandleSsl(true);
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
                                        log.info("JdLoginBot detect {}", url);
                                        loginKey = "http://" + url;
                                        waiter.set();
                                        Thread.sleep(3000);
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
        try {
            lastPoint = bot.clickByImage(jdKey);
            log.info("step1 clickByImage ok");
        } catch (InvalidOperationException e) {
            if (lastPoint != null) {
                bot.mouseLeftClick(lastPoint);
                log.info("step1 retry clickByImage {} ok", lastPoint);
            } else {
                throw e;
            }
        }

        waiter.waitOne(6 * 1000);
        waiter.reset();
        log.info("step2 wait proxy callback");

        try {
            log.info("step2 get key {}", loginKey);
            if (loginKey == null) {
                throw new InvalidOperationException("produce empty key");
            }
            return loginKey;
        } finally {
            loginKey = null;
            TaskFactory.scheduleOnce(() -> {
                log.info("step3 try close it");
//                bot.saveScreen();
                int y = (int) bot.getScreenRectangle().getHeight();
                bot.clickAndAltF4(218, y - 20);
                log.info("step3 closed it");
            }, 2 * 1000);
        }
    }
}
