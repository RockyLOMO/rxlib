package org.rx.common;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.media")
public class FlConfig {
    private int initSize;
    private boolean remoteMode;
    private String remoteEndpoint;
}
