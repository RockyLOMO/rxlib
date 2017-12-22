package org.rx;

import org.rx.util.StringBuilder;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.rx.Contract.isNull;
import static org.rx.Contract.require;

public final class Logger {
    private static final org.slf4j.Logger log1, log2;

    static {
        System.setProperty("BootstrapPath", App.getBootstrapPath());
        System.out.println("BootstrapPath: " + App.getBootstrapPath());
        log1 = LoggerFactory.getLogger("infoLogger");
        log2 = LoggerFactory.getLogger("errorLogger");
    }

    public static org.slf4j.Logger getSlf4j(Class signature) {
        return getSlf4j(signature, Collections.emptyList(), null);
    }

    public static org.slf4j.Logger getSlf4j(Class signature, List<String> regs, String cacheMethodName) {
        require(signature, regs);

        Function<String, org.slf4j.Logger> func = k -> {
            Class owner = signature;
            if (!regs.isEmpty()) {
                String fType;
                if ((fType = NQuery.of(Thread.currentThread().getStackTrace()).select(p -> p.getClassName())
                        .firstOrDefault(p -> NQuery.of(regs).any(reg -> Pattern.matches(reg, p)))) != null) {
                    owner = App.loadClass(fType, false);
                }
            }
            return org.slf4j.LoggerFactory.getLogger(owner);
        };
        return cacheMethodName != null ? App.getOrStore(Logger.class, signature.getName() + cacheMethodName, func)
                : func.apply(null);
    }

    public static void debug(String format, Object... args) {
        debug(null, format, args);
    }

    public static void debug(org.slf4j.Logger log, String format, Object... args) {
        log = isNull(log, log1);
        if (!log.isDebugEnabled()) {
            return;
        }

        String msg = args.length == 0 ? format : String.format(format, args);
        log.debug(msg);
    }

    public static void info(String format, Object... args) {
        info(null, format, args);
    }

    public static void info(org.slf4j.Logger log, String format, Object... args) {
        String msg = args.length == 0 ? format : String.format(format, args);
        isNull(log, log1).info(msg);
    }

    public static void error(Throwable ex, String format, Object... args) {
        error(null, ex, format, args);
    }

    public static void error(org.slf4j.Logger log, Throwable ex, String format, Object... args) {
        String msg = args.length == 0 ? format : String.format(format, args);
        isNull(log, log2).error(msg, ex);
    }

    private org.slf4j.Logger          log;
    private org.rx.util.StringBuilder msg;

    public String getPrefix() {
        return msg.getPrefix();
    }

    public void setPrefix(String prefix) {
        msg.setPrefix(prefix);
    }

    public Logger() {
        this(null);
    }

    public Logger(String loggerName) {
        log = loggerName == null ? log1 : LoggerFactory.getLogger(loggerName);
        msg = new StringBuilder();
    }

    public Logger write(Object obj) {
        log.info(msg.append(obj).toString());
        msg.setLength(0);
        return this;
    }

    public Logger write(String format, Object... args) {
        log.info(msg.append(String.format(format, args)).toString());
        msg.setLength(0);
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
