package org.springframework.service;

import lombok.Data;
import org.springframework.service.SpringContext;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app")
//@Order(Ordered.HIGHEST_PRECEDENCE)
public class MiddlewareConfig {
    private String redisUrl;

    private int limiterPermits = 12;
    private String limiterWhiteList;

    public String[] getLimiterWhiteList() {
        return SpringContext.fromYamlArray(limiterWhiteList);
    }

    private String crawlerEndpoint;
    private String fiddlerEndpoint;

    private SmtpConfig smtp = new SmtpConfig();

    @Data
    public static class SmtpConfig {
        private String host;
        private Integer port;
        private Boolean ssl;
        private Boolean startTls;
        private Integer timeoutMillis;
        private String username;
        private String password;
        private String from;
        private String to;
    }
}
