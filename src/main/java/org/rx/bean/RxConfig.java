package org.rx.bean;

import lombok.Data;
import org.rx.core.*;
import org.rx.io.IOStream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import javax.annotation.PostConstruct;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class RxConfig {
    public static final int KEEPALIVE_MINUTES = 4;

    private String netUserAgent = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2785.116 Safari/537.36 QBCore/4.0.1301.400 QQBrowser/9.0.2524.400 Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2875.116 Safari/537.36 NetType/WIFI MicroMessenger/7.0.5 WindowsWechat";
    private int netTimeoutMillis = 16000;
    private int netMinPoolSize = 1;
    private int netMaxPoolSize;
    private String defaultCache = Cache.LRU_CACHE;
    private int bufferSize = 512;
    private int scheduleDelay = 2000;
    private List<Class<?>> jsonSkipTypes = Arrays.asList(ServletRequest.class, ServletResponse.class, Model.class, IOStream.class);
    private final Set<Class<?>> jsonSkipTypeSet = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void init() {
        bufferSize = Math.max(bufferSize, 128);
        if (netMaxPoolSize <= 0) {
            netMaxPoolSize = ThreadPool.CPU_THREADS;
        }
        if (jsonSkipTypes != null) {
            jsonSkipTypeSet.addAll(jsonSkipTypes);
        }
    }
}
