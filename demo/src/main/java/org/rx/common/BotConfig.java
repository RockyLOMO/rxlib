package org.rx.common;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Data
@Component
@ConfigurationProperties(prefix = "app.bot")
public class BotConfig {
    @Data
    @Component
    @ConfigurationProperties(prefix = "app.bot.wx-mobile")
    public class WxMobileConfig {
        private int capturePeriod;
        private int maxCheckMessageCount;
        private int maxCaptureMessageCount;
        private int maxScrollMessageCount;
    }

    @Resource
    private WxMobileConfig wxMobileConfig;

    private String logPath;
    private int delay;
}
