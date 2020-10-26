package org.rx.core;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;
import org.rx.bean.*;
import org.rx.core.cache.ThreadCache;
import org.rx.security.MD5Util;
import org.rx.io.MemoryStream;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static java.lang.Math.pow;
import static org.rx.core.Contract.*;

@Slf4j
public class App extends SystemUtils {
    static {
        log.debug("init {}", ThreadCache.class);
        System.setProperty("bootstrapPath", getBootstrapPath());
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

    public static List<String> execShell(String workspace, String... shellStrings) {
        List<String> resultList = new ArrayList<>();
        StringBuilder msg = new StringBuilder();
        File dir = null;
        if (workspace != null) {
            msg.append(String.format("execShell workspace=%s\n", workspace));
            dir = new File(workspace);
        }
        for (String shellString : shellStrings) {
            msg.append(String.format("pre-execShell %s", shellString));
            StringBuilder result = new StringBuilder();
            try {
                Process process;
                if (IS_OS_WINDOWS) {
                    process = Runtime.getRuntime().exec(shellString, null, dir);
                } else {
                    process = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", shellString}, null, dir);
                }
                try (LineNumberReader input = new LineNumberReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        result.append(line).append("\n");
                    }
                }
            } catch (Exception e) {
                log.error("execShell", e);
                result.append("ERROR: " + e.getMessage()).append("\n");
            }
            msg.append(String.format("\npost-execShell %s\n\n", result));
            if (result.getLength() == 0) {
                result.append("NULL");
            }
            resultList.add(result.toString());
        }
        log.info(msg.toString());
        return resultList;
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

    public static UUID hash(String key) {
        require(key);

        byte[] guidBytes = MD5Util.md5(key);
        return newUUID(guidBytes);
    }

    public static UUID newComb(boolean sequentialAtEnd) {
        return newComb(null, null);
    }

    public static UUID newComb(String key, Date now) {
        return newComb(key, now, false);
    }

    //http://www.codeproject.com/Articles/388157/GUIDs-as-fast-primary-keys-under-multiple-database
    public static UUID newComb(String key, Date date, boolean sequentialAtEnd) {
        byte[] guidBytes, msecsBytes;
        if (key != null) {
            guidBytes = MD5Util.md5(key);
        } else {
            guidBytes = new byte[16];
            ThreadLocalRandom.current().nextBytes(guidBytes);
        }
        if (date != null) {
            msecsBytes = ByteBuffer.allocate(8).putLong(date.getTime() - DateTime.BaseDate.getTime()).array();
        } else {
            msecsBytes = ByteBuffer.allocate(8).putLong(System.nanoTime() - DateTime.BaseDate.getTime()).array();
        }
        int copyCount = 6, copyOffset = msecsBytes.length - copyCount;
        if (sequentialAtEnd) {
            System.arraycopy(msecsBytes, copyOffset, guidBytes, guidBytes.length - copyCount, copyCount);
        } else {
            System.arraycopy(msecsBytes, copyOffset, guidBytes, 0, copyCount);
        }
        return newUUID(guidBytes);
    }

    private static UUID newUUID(byte[] guidBytes) {
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (guidBytes[i] & 0xff);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (guidBytes[i] & 0xff);
        }
        return new UUID(msb, lsb);
    }
    //endregion

    //region Base64
    public static boolean isBase64String(String base64String) {
        if (Strings.isNullOrEmpty(base64String)) {
            return false;
        }

        return org.apache.commons.codec.binary.Base64.isBase64(base64String);
    }

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
        byte[] data = serialize(obj);
        return convertToBase64String(data);
    }

    @SneakyThrows
    public static <T extends Serializable> byte[] serialize(T obj) {
        require(obj);

        try (MemoryStream stream = new MemoryStream();
             ObjectOutputStream out = new ObjectOutputStream(stream.getWriter())) {
            out.writeObject(obj);
            return stream.toArray();
        }
    }

    public static <T extends Serializable> T deserializeFromBase64(String base64) {
        byte[] data = convertFromBase64String(base64);
        return deserialize(data);
    }

    @SneakyThrows
    public static <T extends Serializable> T deserialize(byte[] data) {
        require(data);

        try (MemoryStream stream = new MemoryStream(data, 0, data.length);
             ObjectInputStream in = new ObjectInputStream(stream.getReader())) {
            return (T) in.readObject();
        }
    }

    public static <T extends Serializable> T deepClone(T obj) {
        byte[] data = serialize(obj);
        return (T) deserialize(data);
    }
    //endregion
}
