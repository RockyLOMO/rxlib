package org.rx.common;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.media")
public class MediaConfig {
    public static String AliPayCode;

    private int coreSize;
    private int maxUserCount;
    private long syncOrderPeriod;
    private int commandTimeout;
    private long cacheSeconds;
    private boolean remoteMode;
    private String remoteEndpoint;
}
