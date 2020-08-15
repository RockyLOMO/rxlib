package org.rx.bean;

import lombok.Data;
import org.rx.core.*;

import java.util.Collections;
import java.util.List;

@Data
public class LibConfig {
    private int sleepMillis = 200;
    private int scheduleDelay = 2000;
    private int bufferSize = 512;
    private int netTimeoutMillis = 16000;
    private int cacheExpireMinutes = 4;
    private boolean autoCompressCacheKey = true;
    private String defaultCache = "LruCache";
    private String[] jsonSkipTypes = new String[]{"javax.servlet.ServletRequest", "javax.servlet.ServletResponse", "org.springframework.ui.Model"};
    private String[] errorCodeFiles = Arrays.EMPTY_STRING_ARRAY;

    public List<Class> getJsonSkipTypesEx() {
        if (Arrays.isEmpty(jsonSkipTypes)) {
            return Collections.emptyList();
        }
        return NQuery.of(jsonSkipTypes).select(p -> (Class) Reflects.loadClass(String.valueOf(p), false)).toList();
    }
}
