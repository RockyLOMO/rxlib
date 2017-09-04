package org.rx.socket;

import java.io.PrintStream;

public class Tracer extends PrintStream {
    private String prefix;

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public Tracer() {
        super(System.out);
    }

    public Tracer write(Object obj) {
        print(prefix);
        print(obj);
        return this;
    }

    public Tracer write(String format, Object... args) {
        print(prefix);
        printf(format, args);
        return this;
    }

    public Tracer writeLine() {
        print(System.lineSeparator());
        return this;
    }

    public Tracer writeLine(Object obj) {
        write(obj).writeLine();
        return this;
    }

    public Tracer writeLine(String format, Object... args) {
        write(format, args).writeLine();
        return this;
    }
}
