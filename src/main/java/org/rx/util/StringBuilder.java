package org.rx.util;

import org.rx.bean.Const;

import static org.rx.Contract.isNull;

public final class StringBuilder {
    private java.lang.StringBuilder buffer;
    private String                  prefix;

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
        this.prefix = isNull(prefix, Const.EmptyString);
    }

    public StringBuilder() {
        this(Const.EmptyString);
    }

    public StringBuilder(String str) {
        buffer = new java.lang.StringBuilder(str);
        prefix = Const.EmptyString;
    }

    public int indexOf(String target) {
        return buffer.indexOf(target);
    }

    public StringBuilder replace(String target, String replacement) {
        int index = 0;
        while ((index = buffer.indexOf(target, index)) != -1) {
            buffer.replace(index, index + target.length(), replacement);
            index += replacement.length(); // Move to the end of the replacement
        }
        return this;
    }

    public StringBuilder insert(int offset, String format, Object... args) {
        return insert(offset, String.format(format, args));
    }

    public StringBuilder insert(int offset, Object obj) {
        buffer.insert(offset, prefix);
        buffer.insert(offset + prefix.length(), obj);
        return this;
    }

    public StringBuilder remove(int offset, int count) {
        buffer.delete(offset, offset + count);
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

    public String toString(int offset, int count) {
        return buffer.substring(offset, offset + count);
    }
}
