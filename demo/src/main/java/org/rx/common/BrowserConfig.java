package org.rx.common;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Data
@Component
@ConfigurationProperties(prefix = "app.browser")
public class BrowserConfig {
    @Data
    @Component
    @ConfigurationProperties(prefix = "app.browser.chrome")
    public static class ChromeConfig {
        private String driver;
        private String dataPath;
        private String downloadPath;
        private boolean isBackground;
        private int initSize;
    }

    @Data
    @Component
    @ConfigurationProperties(prefix = "app.browser.ie")
    public static class IEConfig {
        private String driver;
        private String cookieUrl;
        private int initSize;
    }

    @Resource
    private ChromeConfig chrome;
    @Resource
    private IEConfig ie;

    private String windowRectangle;
    private int errorCountToExchange;
}
