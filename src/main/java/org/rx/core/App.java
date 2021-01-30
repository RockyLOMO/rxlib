package org.rx.core;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;
import org.rx.bean.*;
import org.rx.core.exception.ApplicationException;
import org.rx.core.exception.ExceptionLevel;
import org.rx.core.exception.InvalidException;
import org.rx.io.IOStream;
import org.rx.security.MD5Util;
import org.rx.io.MemoryStream;
import org.rx.spring.SpringContext;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import static java.lang.Math.pow;
import static org.rx.core.Contract.*;

@Slf4j
public class App extends SystemUtils {
    private static RxConfig config;
    private static Predicate<Throwable> ignoreExceptionHandler;

    public static RxConfig getConfig() {
        if (SpringContext.isInitiated()) {
            config = SpringContext.getBean(RxConfig.class);
        }
        if (config == null) {
            config = isNull(readSetting("app", RxConfig.class), new RxConfig());
            config.init();
        }
        return config;
    }

    public static void setIgnoreExceptionHandler(Predicate<Throwable> ignoreExceptionHandler) {
        App.ignoreExceptionHandler = ignoreExceptionHandler;
    }

    //region Basic
    public static String getBootstrapPath() {
        String p = App.class.getClassLoader().getResource("").getFile();
        if (IS_OS_WINDOWS) {
            if (p.startsWith("file:/")) {
                p = p.substring(6);
            } else {
                p = p.substring(1);
            }
        }
        log.debug("bootstrapPath: {}", p);
        return p;
    }

    public static boolean isIgnoringException(Throwable e) {
        if (ignoreExceptionHandler == null) {
            return false;
        }
        return ignoreExceptionHandler.test(e);
    }

    public static String log(String key, Throwable e) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Reflects.stackClass(1));
        if (isIgnoringException(e)) {
            log.info("{} {}", key, e.getMessage());
        } else {
            InvalidException invalidException = as(e, InvalidException.class);
            if (invalidException != null) {
                switch (isNull(invalidException.getLevel(), ExceptionLevel.SYSTEM)) {
                    case USER_OPERATION:
                        log.warn("{} {}", key, e.getMessage());
                        break;
                    case IGNORE:
                        log.info("{} {}", key, e.getMessage());
                        break;
                    default:
                        log.error(key, e);
                        Tasks.raiseUncaughtException(e);
                        break;
                }
            }
        }
        ApplicationException applicationException = as(e, ApplicationException.class);
        if (applicationException == null) {
            return isNull(e.getMessage(), ApplicationException.DEFAULT_MESSAGE);
        }
        return applicationException.getFriendlyMessage();
    }

    /**
     * 简单的计算字符串
     *
     * @param expression 字符串
     * @return 计算结果
     */
    public static double simpleEval(final String expression) {
        return new Object() {
            int pos = -1, ch;

            void nextChar() {
                ch = (++pos < expression.length()) ? expression.charAt(pos) : -1;
            }

            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) {
                    nextChar();
                    return true;
                }
                return false;
            }

            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < expression.length()) throw new RuntimeException("Unexpected: " + (char) ch);
                return x;
            }

            // Grammar:
            // expression = term | expression `+` term | expression `-` term
            // term = factor | term `*` factor | term `/` factor
            // factor = `+` factor | `-` factor | `(` expression `)`
            //        | number | functionName factor | factor `^` factor

            double parseExpression() {
                double x = parseTerm();
                for (; ; ) {
                    if (eat('+')) x += parseTerm(); // addition
                    else if (eat('-')) x -= parseTerm(); // subtraction
                    else return x;
                }
            }

            double parseTerm() {
                double x = parseFactor();
                for (; ; ) {
                    if (eat('*')) x *= parseFactor(); // multiplication
                    else if (eat('/')) x /= parseFactor(); // division
                    else return x;
                }
            }

            double parseFactor() {
                if (eat('+')) return parseFactor(); // unary plus
                if (eat('-')) return -parseFactor(); // unary minus

                double x;
                int startPos = this.pos;

                if (eat('(')) { // parentheses
                    x = parseExpression();
                    eat(')');
                } else if ((ch >= '0' && ch <= '9') || ch == '.') { // numbers
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(expression.substring(startPos, this.pos));
                } else throw new RuntimeException("Unexpected: " + (char) ch);

                if (eat('^')) x = pow(x, parseFactor()); // exponentiation

                return x;
            }
        }.parse();
    }

    public static UUID hash(Object... args) {
        return hash(Strings.joinWith(Strings.EMPTY, args));
    }

    public static UUID hash(String key) {
        require(key);

        byte[] guidBytes = MD5Util.md5(key);
        return SUID.newUUID(guidBytes);
    }

    public static UUID combId() {
        return combId(System.nanoTime(), null);
    }

    public static UUID combId(Timestamp timestamp, String key) {
        return combId(timestamp.getTime(), key);
    }

    public static UUID combId(long timestamp, String key) {
        return combId(timestamp, key, false);
    }

    //http://www.codeproject.com/Articles/388157/GUIDs-as-fast-primary-keys-under-multiple-database
    public static UUID combId(long timestamp, String key, boolean sequentialAtEnd) {
        long id;
        if (key != null) {
            id = ByteBuffer.wrap(MD5Util.md5(key)).getLong(4);
        } else {
            id = ThreadLocalRandom.current().nextLong();
        }
        long mostSigBits, leastSigBits;
        if (sequentialAtEnd) {
            mostSigBits = id;
            leastSigBits = timestamp;
        } else {
            mostSigBits = timestamp;
            leastSigBits = id;
        }
        return new UUID(mostSigBits, leastSigBits);
    }
    //endregion

    //region Base64
    //org.apache.commons.codec.binary.Base64.isBase64(base64String) 不准
    @SneakyThrows
    public static String convertToBase64String(byte[] data) {
        require(data);

        byte[] ret = Base64.getEncoder().encode(data);
        return new String(ret, StandardCharsets.UTF_8);
    }

    @SneakyThrows
    public static byte[] convertFromBase64String(String base64) {
        require(base64);

        byte[] data = base64.getBytes(StandardCharsets.UTF_8);
        return Base64.getDecoder().decode(data);
    }

    public static <T extends Serializable> String serializeToBase64(T obj) {
        byte[] data = IOStream.toBytes(IOStream.serialize(obj));
        return convertToBase64String(data);
    }

    public static <T extends Serializable> T deserializeFromBase64(String base64) {
        byte[] data = convertFromBase64String(base64);
        return IOStream.deserialize(new MemoryStream(data, 0, data.length));
    }

    public static <T extends Serializable> T deepClone(T obj) {
        IOStream<?, ?> serialize = IOStream.serialize(obj);
        return IOStream.deserialize(serialize);
    }
    //endregion
}
