package org.rx;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public final class Logger {
    private static final Log log1 = LogFactory.getLog("infoLogger"), log2 = LogFactory.getLog("errorLogger");

    public static void debug(String format, Object... args) {
        String msg = args.length == 0 ? format : String.format(format, args);
        System.out.println(msg);
        log1.debug(msg);
    }

    public static void info(String format, Object... args) {
        String msg = args.length == 0 ? format : String.format(format, args);
        log1.info(msg + System.lineSeparator());
    }

    public static void error(Throwable ex, String format, Object... args) {
        String msg = args.length == 0 ? format : String.format(format, args);
        log2.error(String.format("%s%s %s", System.lineSeparator(), ex.getMessage(), msg), ex);
    }
}
