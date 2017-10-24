package org.rx.bean;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class Const {
    public interface SettingNames {
        String JsonSkipTypes  = "app.jsonSkipTypes";
        String ErrorCodeFiles = "app.errorCodeFiles";
    }

    public static final int      DefaultBufferSize;
    public static final String   SettingsFile, EmptyString, Utf8;
    public static final String   AllWarnings = "all";
    public static final Object[] EmptyArray;
    public static final List     EmptyList;
    public static final Map      EmptyMap;

    static {
        DefaultBufferSize = 1024;
        SettingsFile = "application";
        EmptyString = "";
        Utf8 = "UTF-8";
        EmptyArray = new Object[0];
        EmptyList = Collections.EMPTY_LIST;
        EmptyMap = Collections.EMPTY_MAP;
    }
}
