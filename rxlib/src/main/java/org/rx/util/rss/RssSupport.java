package org.rx.util.rss;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Sys;
import org.rx.diagnostic.DiagnosticMonitor;
import org.rx.net.OptimalSettings;
import org.rx.net.http.HttpClient;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.UdpCompressCodec;
import org.rx.net.socks.UdpCompressConfig;

@Slf4j
public final class RssSupport {
    public static final OptimalSettings OUT_OPS = new OptimalSettings((int) (640 * 0.8), 150, 60, 1000, OptimalSettings.Mode.LOW_LATENCY);
    public static final OptimalSettings IN_OPS = null;
    public static final OptimalSettings SS_IN_OPS = new OptimalSettings((int) (1024 * 0.8), 30, 200, 2000, OptimalSettings.Mode.BALANCED);
    public static final int TCP_TRIAL_COMPRESSION_LEVEL = 5;

    /** Dynadot、/hf 等复用，避免每次 new HttpClient */
    static final HttpClient MAIN_HTTP_CLIENT = new HttpClient();

    private RssSupport() {
    }

    static void bootstrapRuntime() throws ClassNotFoundException {
        Class.forName(Sys.class.getName());
        DiagnosticMonitor.startDefault();
    }

    static void applyUdpCompressionTrial(SocksConfig config) {
        config.setUdpCompressEnabled(true);
        config.setUdpCompressCodec(UdpCompressCodec.LZ4_FAST);
        config.setUdpCompressCompressionLevel(UdpCompressConfig.DEFAULT_COMPRESSION_LEVEL);
        config.setUdpCompressMinPayloadBytes(96);
        config.setUdpCompressMinSavingsBytes(24);
        config.setUdpCompressMinSavingsRatio(0.12D);
        config.setUdpCompressAdaptiveBypass(true);
        config.setUdpCompressAdaptiveBypassWindowSeconds(30);
    }
}
