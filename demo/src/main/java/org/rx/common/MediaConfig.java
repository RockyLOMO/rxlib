package org.rx.common;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Data
@Component
@ConfigurationProperties(prefix = "app.media")
public class MediaConfig {
    @Data
    @Component
    @ConfigurationProperties(prefix = "app.media.taobao")
    public class TaobaoConfig {
        private int coreSize;
        private int keepLoginSeconds;
    }

    @Data
    @Component
    @ConfigurationProperties(prefix = "app.media.jd")
    public class JdConfig {
        private int coreSize;
        private int keepLoginSeconds;
        private int loginPort;
    }

    public static String AliPayCode;

    @Resource
    private TaobaoConfig taobaoConfig;
    @Resource
    private JdConfig jdConfig;

    private String enableMedias;
    private int syncWeeklyOrderSeconds;
    private int syncMonthlyOrderSeconds;
    private int commandTimeout;
    private int goodsCacheMinutes;
    private int advCacheMinutes;
    private boolean remoteMode;
    private String remoteEndpoint;
}
