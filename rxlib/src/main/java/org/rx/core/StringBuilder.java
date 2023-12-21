package org.rx.core;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.rx.util.function.TripleAction;

import java.io.IOException;
import java.io.Serializable;

@Getter
public final class StringBuilder implements Appendable, CharSequence, Serializable {
    private static final long serialVersionUID = -5807000410250350182L;
    final java.lang.StringBuilder buffer;
    @Setter
    TripleAction<StringBuilder, Object> preAppend;

    public boolean isEmpty() {
        return length() == 0;
    }

    public StringBuilder() {
        buffer = new java.lang.StringBuilder();
    }

    public StringBuilder(int capacity) {
        buffer = new java.lang.StringBuilder(capacity);
    }

    public StringBuilder(CharSequence seq) {
        buffer = new java.lang.StringBuilder(seq);
    }

    @Override
    public int length() {
        return buffer.length();
    }

    public StringBuilder setLength(int length) {
        buffer.setLength(length);
        return this;
    }

    @Override
    public char charAt(int index) {
        return buffer.charAt(index);
    }

    public StringBuilder setCharAt(int index, char ch) {
        buffer.setCharAt(index, ch);
        return this;
    }

    public int indexOf(String str) {
        return buffer.indexOf(str);
    }

    public int indexOf(String str, int fromIndex) {
        return buffer.indexOf(str, fromIndex);
    }

    public int lastIndexOf(String str) {
        return buffer.lastIndexOf(str);
    }

    public int lastIndexOf(String str, int fromIndex) {
        return buffer.lastIndexOf(str, fromIndex);
    }

    public StringBuilder insert(int offset, Object obj) {
        buffer.insert(offset, obj);
        return this;
    }

    public StringBuilder insert(int offset, String str) {
        buffer.insert(offset, str);
        return this;
    }

    public StringBuilder insert(int offset, String format, Object... args) {
        return insert(offset, String.format(format, args));
    }

    public StringBuilder insert(int dstOffset, CharSequence s) {
        buffer.insert(dstOffset, s);
        return this;
    }

    public StringBuilder insert(int dstOffset, CharSequence s, int start, int end) {
        buffer.insert(dstOffset, s, start, end);
        return this;
    }

    public StringBuilder insert(int offset, boolean p) {
        buffer.insert(offset, p);
        return this;
    }

    public StringBuilder insert(int offset, char p) {
        buffer.insert(offset, p);
        return this;
    }

    public StringBuilder insert(int offset, int p) {
        buffer.insert(offset, p);
        return this;
    }

    public StringBuilder insert(int offset, long p) {
        buffer.insert(offset, p);
        return this;
    }

    public StringBuilder insert(int offset, float p) {
        buffer.insert(offset, p);
        return this;
    }

    public StringBuilder insert(int offset, double p) {
        buffer.insert(offset, p);
        return this;
    }

    public StringBuilder replace(String target, String replacement) {
        int index = 0;
        while ((index = buffer.indexOf(target, index)) != -1) {
            buffer.replace(index, index + target.length(), replacement);
            index += replacement.length(); // Move to the end of the replacement
        }
        return this;
    }

    public StringBuilder replace(int start, int end, String str) {
        buffer.replace(start, end, str);
        return this;
    }

    public StringBuilder delete(int offset, int length) {
        buffer.delete(offset, offset + length);
        return this;
    }

    public StringBuilder deleteCharAt(int index) {
        buffer.deleteCharAt(index);
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
        buffer.append(csq);
        return this;
    }

    public StringBuilder append(Object obj) {
        preAppend(obj);
        buffer.append(obj);
        return this;
    }

    public StringBuilder append(String str) {
        preAppend(str);
        buffer.append(str);
        return this;
    }

    public StringBuilder append(String format, Object... args) {
        return append(String.format(format, args));
    }

    @Override
    public StringBuilder append(CharSequence csq, int start, int end) {
        buffer.append(csq, start, end);
        return this;
    }

    @Override
    public StringBuilder append(char p) {
        buffer.append(p);
        return this;
    }

    public StringBuilder append(boolean p) {
        buffer.append(p);
        return this;
    }

    public StringBuilder append(int p) {
        buffer.append(p);
        return this;
    }

    public StringBuilder append(long p) {
        buffer.append(p);
        return this;
    }

    public StringBuilder append(float p) {
        buffer.append(p);
        return this;
    }

    public StringBuilder append(double p) {
        buffer.append(p);
        return this;
    }

    public StringBuilder appendLine() {
        buffer.append(System.lineSeparator());
        return this;
    }

    public StringBuilder appendLine(Object obj) {
        preAppend(obj);
        buffer.append(obj);
        return appendLine();
    }

    public StringBuilder appendLine(String format, Object... args) {
        return appendLine(String.format(format, args));
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return buffer.subSequence(start, end);
    }

    public String substring(int start) {
        return buffer.substring(start);
    }

    public String substring(int start, int end) {
        return buffer.substring(start, end);
    }

    @Override
    public String toString() {
        return buffer.toString();
    }

    public String toString(int offset, int length) {
        return buffer.substring(offset, offset + length);
    }
}
