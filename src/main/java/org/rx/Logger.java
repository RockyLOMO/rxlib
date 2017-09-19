package org.rx;

import org.slf4j.LoggerFactory;

public final class Logger {
    private static final org.slf4j.Logger log1, log2;

    static {
        System.setProperty("BootstrapPath", App.getBootstrapPath());
        System.out.println("BootstrapPath: " + App.getBootstrapPath());
        log1 = LoggerFactory.getLogger("infoLogger");
        log2 = LoggerFactory.getLogger("errorLogger");
    }

    public static void debug(String format, Object... args) {
        String msg = args.length == 0 ? format : String.format(format, args);
        log1.debug(msg);
    }

    public static void info(String format, Object... args) {
        String msg = args.length == 0 ? format : String.format(format, args);
        log1.info(msg + System.lineSeparator());
    }

    public static void error(Throwable ex, String format, Object... args) {
        String msg = args.length == 0 ? format : String.format(format, args);
        log2.error(msg + System.lineSeparator(), ex);
    }
}
