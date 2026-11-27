package org.rx.util.rss;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Sys;
import org.rx.diagnostic.DiagnosticMonitor;
import org.rx.net.OptimalSettings;
import org.rx.net.http.HttpClient;

@Slf4j
public final class RssSupport {
    public static final OptimalSettings OUT_OPS = new OptimalSettings((int) (640 * 0.8), 150, 60, 1000, OptimalSettings.Mode.LOW_LATENCY);
    public static final OptimalSettings IN_OPS = null;
    public static final OptimalSettings SS_IN_OPS = new OptimalSettings((int) (1024 * 0.8), 30, 200, 2000, OptimalSettings.Mode.BALANCED);
    public static final int TCP_COMPRESSION_LEVEL = 3;

    /** Dynadot、/hf 等复用，避免每次 new HttpClient */
    static final HttpClient MAIN_HTTP_CLIENT = new HttpClient();

    static final class DdnsHttp {
        static final HttpClient CLIENT = create();
        @lombok.SneakyThrows
        private static HttpClient create() {
            org.rx.net.http.HttpClientConfig config = new org.rx.net.http.HttpClientConfig().setTimeoutMillis(5000)
                    .setSslContext(io.netty.handler.ssl.SslContextBuilder.forClient()
                            .sslProvider(io.netty.handler.ssl.SslProvider.JDK).build());
            config.setVerifyHostname(true);
            config.setEnableLog(false); config.setFollowRedirects(false);
            return new HttpClient(config);
        }
    }

    private RssSupport() {
    }

    static void bootstrapRuntime() throws ClassNotFoundException {
        Class.forName(Sys.class.getName());
        DiagnosticMonitor.startDefault();
    }
}
