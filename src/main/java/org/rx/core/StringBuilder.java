package org.rx.core;

import static org.rx.core.App.isNull;

public final class StringBuilder {
    private final java.lang.StringBuilder buffer;
    private String prefix = Strings.EMPTY;

    public java.lang.StringBuilder getBuffer() {
        return buffer;
    }

    public int getLength() {
        return buffer.length();
    }

    public StringBuilder setLength(int length) {
        buffer.setLength(length);
        return this;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = isNull(prefix, Strings.EMPTY);
    }

    public StringBuilder() {
        this(32);
    }

    public StringBuilder(int capacity) {
        buffer = new java.lang.StringBuilder(capacity);
    }

    public StringBuilder(String str) {
        buffer = new java.lang.StringBuilder(str);
    }

    public int indexOf(String target) {
        return indexOf(target, 0);
    }

    public int indexOf(String target, int fromIndex) {
        return buffer.indexOf(target, fromIndex);
    }

    public int lastIndexOf(String target) {
        return lastIndexOf(target, getLength());
    }

    public int lastIndexOf(String target, int fromIndex) {
        return buffer.lastIndexOf(target, fromIndex);
    }

    public StringBuilder replace(String target, String replacement) {
        int index = 0;
        while ((index = buffer.indexOf(target, index)) != -1) {
            buffer.replace(index, index + target.length(), replacement);
            index += replacement.length(); // Move to the end of the replacement
        }
        return this;
    }

    public String substring(int start) {
        return substring(start, getLength());
    }

    public String substring(int start, int end) {
        return buffer.substring(start, end);
    }

    public StringBuilder insert(int offset, String format, Object... args) {
        return insert(offset, String.format(format, args));
    }

    public StringBuilder insert(int offset, Object obj) {
        buffer.insert(offset, prefix);
        buffer.insert(offset + prefix.length(), obj);
        return this;
    }

    public StringBuilder remove(int offset, int length) {
        buffer.delete(offset, offset + length);
        return this;
    }

    public StringBuilder append(String format, Object... args) {
        return append(String.format(format, args));
    }

    public StringBuilder append(Object obj) {
        buffer.append(prefix).append(obj);
        return this;
    }

    public StringBuilder appendLine() {
        buffer.append(System.lineSeparator());
        return this;
    }

    public StringBuilder appendLine(Object obj) {
        buffer.append(prefix).append(obj);
        return appendLine();
    }

    public StringBuilder appendLine(String format, Object... args) {
        return appendLine(String.format(format, args));
    }

    @Override
    public String toString() {
        return buffer.toString();
    }

    public String toString(int offset, int length) {
        return buffer.substring(offset, offset + length);
    }
}
