package org.rx.beans;

import lombok.Data;
import org.rx.core.Arrays;

@Data
public class AppConfig {
    private int sleepMillis = 200;
    private int scheduleDelay = 2000;
    private int bufferSize = 512;
    private int socksTimeout = 16000;
    private int cacheLiveSeconds = 120;
    private String[] jsonSkipTypes = Arrays.EMPTY_STRING_ARRAY;
    private String[] errorCodeFiles = Arrays.EMPTY_STRING_ARRAY;
}
