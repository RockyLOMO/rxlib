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
    }

    @Data
    @Component
    @ConfigurationProperties(prefix = "app.media.jd")
    public class JdConfig {
        private int coreSize;
    }

    public static String AliPayCode;

    @Resource
    private TaobaoConfig taobaoConfig;
    @Resource
    private JdConfig jdConfig;

    private int maxUserCount;
    private int syncWeeklyOrderSeconds;
    private int syncMonthlyOrderSeconds;
    private int commandTimeout;
    private int cacheSeconds;
    private boolean remoteMode;
    private String remoteEndpoint;
}
