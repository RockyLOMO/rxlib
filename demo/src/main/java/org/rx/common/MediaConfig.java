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
    private int syncWeeklyOrderSeconds;
    private int syncMonthlyOrderSeconds;
    private int commandTimeout;
    private int cacheSeconds;
    private boolean remoteMode;
    private String remoteEndpoint;
}
