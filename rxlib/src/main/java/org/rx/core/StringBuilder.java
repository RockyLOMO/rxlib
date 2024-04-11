package org.rx.core;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.rx.util.function.TripleAction;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

@Getter
public final class StringBuilder implements Appendable, CharSequence, Serializable {
    private static final long serialVersionUID = -5807000410250350182L;
    final java.lang.StringBuilder buf;
    @Setter
    TripleAction<StringBuilder, Object> preAppend;

    public boolean isEmpty() {
        return length() == 0;
    }

    public StringBuilder() {
        buf = new java.lang.StringBuilder();
    }

    public StringBuilder(int capacity) {
        buf = new java.lang.StringBuilder(capacity);
    }

    public StringBuilder(CharSequence seq) {
        buf = new java.lang.StringBuilder(seq);
    }

    @Override
    public int length() {
        return buf.length();
    }

    public StringBuilder setLength(int length) {
        buf.setLength(length);
        return this;
    }

    @Override
    public char charAt(int index) {
        return buf.charAt(index);
    }

    public StringBuilder setCharAt(int index, char ch) {
        buf.setCharAt(index, ch);
        return this;
    }

    public int indexOf(String str) {
        return buf.indexOf(str);
    }

    public int indexOf(String str, int fromIndex) {
        return buf.indexOf(str, fromIndex);
    }

    public int lastIndexOf(String str) {
        return buf.lastIndexOf(str);
    }

    public int lastIndexOf(String str, int fromIndex) {
        return buf.lastIndexOf(str, fromIndex);
    }

    public StringBuilder insert(int offset, Object obj) {
        buf.insert(offset, obj);
        return this;
    }

    public StringBuilder insert(int offset, String str) {
        buf.insert(offset, str);
        return this;
    }

    public StringBuilder insert(int offset, String format, Object... args) {
        return insert(offset, String.format(format, args));
    }

    public StringBuilder insert(int dstOffset, CharSequence s) {
        buf.insert(dstOffset, s);
        return this;
    }

    public StringBuilder insert(int dstOffset, CharSequence s, int start, int end) {
        buf.insert(dstOffset, s, start, end);
        return this;
    }

    public StringBuilder insert(int offset, boolean p) {
        buf.insert(offset, p);
        return this;
    }

    public StringBuilder insert(int offset, char p) {
        buf.insert(offset, p);
        return this;
    }

    public StringBuilder insert(int offset, int p) {
        buf.insert(offset, p);
        return this;
    }

    public StringBuilder insert(int offset, long p) {
        buf.insert(offset, p);
        return this;
    }

    public StringBuilder insert(int offset, float p) {
        buf.insert(offset, p);
        return this;
    }

    public StringBuilder insert(int offset, double p) {
        buf.insert(offset, p);
        return this;
    }

    public StringBuilder replace(String target, String replacement) {
        int index = 0;
        while ((index = buf.indexOf(target, index)) != -1) {
            buf.replace(index, index + target.length(), replacement);
            index += replacement.length(); // Move to the end of the replacement
        }
        return this;
    }

    public StringBuilder replace(int start, int end, String str) {
        buf.replace(start, end, str);
        return this;
    }

    public StringBuilder delete(int offset, int length) {
        buf.delete(offset, offset + length);
        return this;
    }

    public StringBuilder deleteCharAt(int index) {
        buf.deleteCharAt(index);
        return this;
    }

    @SneakyThrows
    void preAppend(Object obj) {
        if (preAppend == null) {
            return;
        }
        preAppend.invoke(this, obj);
    }

    @Override
    public StringBuilder append(CharSequence csq) {
        preAppend(csq);
        buf.append(csq);
        return this;
    }

    public StringBuilder append(Object obj) {
        preAppend(obj);
        buf.append(obj);
        return this;
    }

    public StringBuilder append(String str) {
        preAppend(str);
        buf.append(str);
        return this;
    }

    @Override
    public StringBuilder append(CharSequence csq, int start, int end) {
        buf.append(csq, start, end);
        return this;
    }

    @Override
    public StringBuilder append(char p) {
        buf.append(p);
        return this;
    }

    public StringBuilder append(boolean p) {
        buf.append(p);
        return this;
    }

    public StringBuilder append(int p) {
        buf.append(p);
        return this;
    }

    public StringBuilder append(long p) {
        buf.append(p);
        return this;
    }

    public StringBuilder append(float p) {
        buf.append(p);
        return this;
    }

    public StringBuilder append(double p) {
        buf.append(p);
        return this;
    }

    public StringBuilder appendLine() {
        buf.append(System.lineSeparator());
        return this;
    }

    public StringBuilder appendLine(Object obj) {
        preAppend(obj);
        buf.append(obj);
        return appendLine();
    }

    public StringBuilder appendFormat(String format, Object... args) {
        return append(String.format(format, args));
    }

    public StringBuilder appendMessageFormat(String format, Object... args) {
        appendArrayFormat(format, args);
        return this;
    }

    //region arrayFormat;
    static final char DELIM_START = '{';
    static final String DELIM_STR = "{}";
    static final char ESCAPE_CHAR = '\\';

    void appendArrayFormat(final String messagePattern, final Object[] argArray) {
        if (messagePattern == null) {
            buf.append("null");
            return;
        }
        if (argArray == null) {
            buf.append(messagePattern);
            return;
        }

        int i = 0;
        int j;
        buf.ensureCapacity(buf.length() + messagePattern.length() + 50);

        int L;
        for (L = 0; L < argArray.length; L++) {
            j = messagePattern.indexOf(DELIM_STR, i);
            if (j == -1) {
                // no more variables
                if (i == 0) { // this is a simple string
                    buf.append(messagePattern);
                    return;
                } else { // add the tail string which contains no variables and return
                    // the result.
                    buf.append(messagePattern, i, messagePattern.length());
                    return;
                }
            } else {
                if (isEscapedDelimeter(messagePattern, j)) {
                    if (!isDoubleEscaped(messagePattern, j)) {
                        L--; // DELIM_START was escaped, thus should not be incremented
                        buf.append(messagePattern, i, j - 1);
                        buf.append(DELIM_START);
                        i = j + 1;
                    } else {
                        // The escape character preceding the delimiter start is
                        // itself escaped: "abc x:\\{}"
                        // we have to consume one backward slash
                        buf.append(messagePattern, i, j - 1);
                        deeplyAppendParameter(buf, argArray[L], new HashMap<>());
                        i = j + 2;
                    }
                } else {
                    // normal case
                    buf.append(messagePattern, i, j);
                    deeplyAppendParameter(buf, argArray[L], new HashMap<>());
                    i = j + 2;
                }
            }
        }
        // append the characters following the last {} pair.
        buf.append(messagePattern, i, messagePattern.length());
    }

    static boolean isEscapedDelimeter(String messagePattern, int delimeterStartIndex) {
        if (delimeterStartIndex == 0) {
            return false;
        }
        char potentialEscape = messagePattern.charAt(delimeterStartIndex - 1);
        return potentialEscape == ESCAPE_CHAR;
    }

    static boolean isDoubleEscaped(String messagePattern, int delimeterStartIndex) {
        return delimeterStartIndex >= 2 && messagePattern.charAt(delimeterStartIndex - 2) == ESCAPE_CHAR;
    } // special treatment of array values was suggested by 'lizongbo'

    private static void deeplyAppendParameter(java.lang.StringBuilder sbuf, Object o, Map<Object[], Object> seenMap) {
        if (o == null) {
            sbuf.append("null");
            return;
        }
        if (!o.getClass().isArray()) {
            safeObjectAppend(sbuf, o);
        } else {
            // check for primitive array types because they
            // unfortunately cannot be cast to Object[]
            if (o instanceof boolean[]) {
                booleanArrayAppend(sbuf, (boolean[]) o);
            } else if (o instanceof byte[]) {
                byteArrayAppend(sbuf, (byte[]) o);
            } else if (o instanceof char[]) {
                charArrayAppend(sbuf, (char[]) o);
            } else if (o instanceof short[]) {
                shortArrayAppend(sbuf, (short[]) o);
            } else if (o instanceof int[]) {
                intArrayAppend(sbuf, (int[]) o);
            } else if (o instanceof long[]) {
                longArrayAppend(sbuf, (long[]) o);
            } else if (o instanceof float[]) {
                floatArrayAppend(sbuf, (float[]) o);
            } else if (o instanceof double[]) {
                doubleArrayAppend(sbuf, (double[]) o);
            } else {
                objectArrayAppend(sbuf, (Object[]) o, seenMap);
            }
        }
    }

    private static void safeObjectAppend(java.lang.StringBuilder sbuf, Object o) {
        try {
            String oAsString = o.toString();
            sbuf.append(oAsString);
        } catch (Throwable t) {
            sbuf.append("[FAILED toString()]");
        }
    }

    private static void objectArrayAppend(java.lang.StringBuilder sbuf, Object[] a, Map<Object[], Object> seenMap) {
        sbuf.append('[');
        if (!seenMap.containsKey(a)) {
            seenMap.put(a, null);
            final int len = a.length;
            for (int i = 0; i < len; i++) {
                deeplyAppendParameter(sbuf, a[i], seenMap);
                if (i != len - 1)
                    sbuf.append(", ");
            }
            // allow repeats in siblings
            seenMap.remove(a);
        } else {
            sbuf.append("...");
        }
        sbuf.append(']');
    }

    private static void booleanArrayAppend(java.lang.StringBuilder sbuf, boolean[] a) {
        sbuf.append('[');
        final int len = a.length;
        for (int i = 0; i < len; i++) {
            sbuf.append(a[i]);
            if (i != len - 1)
                sbuf.append(", ");
        }
        sbuf.append(']');
    }

    private static void byteArrayAppend(java.lang.StringBuilder sbuf, byte[] a) {
        sbuf.append('[');
        final int len = a.length;
        for (int i = 0; i < len; i++) {
            sbuf.append(a[i]);
            if (i != len - 1)
                sbuf.append(", ");
        }
        sbuf.append(']');
    }

    private static void charArrayAppend(java.lang.StringBuilder sbuf, char[] a) {
        sbuf.append('[');
        final int len = a.length;
        for (int i = 0; i < len; i++) {
            sbuf.append(a[i]);
            if (i != len - 1)
                sbuf.append(", ");
        }
        sbuf.append(']');
    }

    private static void shortArrayAppend(java.lang.StringBuilder sbuf, short[] a) {
        sbuf.append('[');
        final int len = a.length;
        for (int i = 0; i < len; i++) {
            sbuf.append(a[i]);
            if (i != len - 1)
                sbuf.append(", ");
        }
        sbuf.append(']');
    }

    private static void intArrayAppend(java.lang.StringBuilder sbuf, int[] a) {
        sbuf.append('[');
        final int len = a.length;
        for (int i = 0; i < len; i++) {
            sbuf.append(a[i]);
            if (i != len - 1)
                sbuf.append(", ");
        }
        sbuf.append(']');
    }

    private static void longArrayAppend(java.lang.StringBuilder sbuf, long[] a) {
        sbuf.append('[');
        final int len = a.length;
        for (int i = 0; i < len; i++) {
            sbuf.append(a[i]);
            if (i != len - 1)
                sbuf.append(", ");
        }
        sbuf.append(']');
    }

    private static void floatArrayAppend(java.lang.StringBuilder sbuf, float[] a) {
        sbuf.append('[');
        final int len = a.length;
        for (int i = 0; i < len; i++) {
            sbuf.append(a[i]);
            if (i != len - 1)
                sbuf.append(", ");
        }
        sbuf.append(']');
    }

    private static void doubleArrayAppend(java.lang.StringBuilder sbuf, double[] a) {
        sbuf.append('[');
        final int len = a.length;
        for (int i = 0; i < len; i++) {
            sbuf.append(a[i]);
            if (i != len - 1)
                sbuf.append(", ");
        }
        sbuf.append(']');
    }
    //endregion

    public StringBuilder appendLine(String format, Object... args) {
        return appendLine(String.format(format, args));
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return buf.subSequence(start, end);
    }

    public String substring(int start) {
        return buf.substring(start);
    }

    public String substring(int start, int end) {
        return buf.substring(start, end);
    }

    @Override
    public String toString() {
        return buf.toString();
    }

    public String toString(int offset, int length) {
        return buf.substring(offset, offset + length);
    }
}
