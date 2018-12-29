package org.rx.common;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.web")
public class FlConfig {
    private String driver;
    private String dataPath;
    private boolean remoteMedia;
    private String remoteEndpoint;
}
