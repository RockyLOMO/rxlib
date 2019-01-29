package org.rx.common;

import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.rx.common.Contract.require;

public final class Logger {
    static final org.slf4j.Logger log1, log2;

    static {
        System.setProperty("bootstrapPath", App.getBootstrapPath());
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
        return cacheMethodName != null ? App.getOrStore("Logger" + signature.getName() + cacheMethodName, func)
                : func.apply(null);
    }

    public static void debug(String format, Object... args) {
        if (!log1.isDebugEnabled()) {
            return;
        }

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
}
