package org.rx.bean;

import lombok.Data;
import org.rx.core.*;

import java.util.Collections;
import java.util.List;

@Data
public class RxConfig {
    private String netUserAgent = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2785.116 Safari/537.36 QBCore/4.0.1301.400 QQBrowser/9.0.2524.400 Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2875.116 Safari/537.36 NetType/WIFI MicroMessenger/7.0.5 WindowsWechat";
    private int netTimeoutMillis = 16000;
    private int netMinPoolSize = 2;
    private int netMaxPoolSize;
    private int cacheExpireMinutes = 4;
    private String defaultCache = Cache.LRU_CACHE;
    private int bufferSize = 512;
    private int scheduleDelay = 2000;
    private int sleepMillis = 200;
    private String[] jsonSkipTypes = new String[]{"javax.servlet.ServletRequest", "javax.servlet.ServletResponse", "org.springframework.ui.Model"};
    private String[] errorCodeFiles = Arrays.EMPTY_STRING_ARRAY;

    public List<Class> getJsonSkipTypesEx() {
        if (Arrays.isEmpty(jsonSkipTypes)) {
            return Collections.emptyList();
        }
        return NQuery.of(jsonSkipTypes).select(p -> (Class) Reflects.loadClass(String.valueOf(p), false)).toList();
    }
}
