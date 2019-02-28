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
        private int rootPercent;
        private String guideUrl;
    }

    @Data
    @Component
    @ConfigurationProperties(prefix = "app.media.jd")
    public class JdConfig {
        private int coreSize;
        private int keepLoginSeconds;
        private int loginPort;
        private int rootPercent;
        private String guideUrl;
    }

    @Data
    @Component
    @ConfigurationProperties(prefix = "app.media.pdd")
    public class PddConfig {
        private int coreSize;
        private int keepLoginSeconds;
    }

    @Resource
    private TaobaoConfig taobao;
    @Resource
    private JdConfig jd;
    @Resource
    private PddConfig pdd;

    private String enableMedias;
    private int syncWeeklyOrderSeconds;
    private String syncMonthlyOrderTime;
    private int commandTimeout;
    private int goodsCacheMinutes;
    private int advCacheMinutes;
    private boolean remoteMode;
    private String remoteEndpoint;
}
