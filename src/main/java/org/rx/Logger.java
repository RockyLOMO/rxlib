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
        log1.info(msg);
    }

    public static void error(Throwable ex, String format, Object... args) {
        String msg = args.length == 0 ? format : String.format(format, args);
        log2.error(msg, ex);
    }

    private org.slf4j.Logger log;
    private String           prefix;

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public Logger() {
        this(null);
    }

    public Logger(String loggerName) {
        log = loggerName == null ? log1 : LoggerFactory.getLogger(loggerName);
    }

    public Logger write(Object obj) {
        log.info(new StringBuilder(prefix).append(obj).toString());
        return this;
    }

    public Logger write(String format, Object... args) {
        log.info(new StringBuilder(prefix).append(String.format(format, args)).toString());
        return this;
    }

    public Logger writeLine(Object obj) {
        write(obj + System.lineSeparator());
        return this;
    }

    public Logger writeLine(String format, Object... args) {
        write(format + System.lineSeparator(), args);
        return this;
    }
}
